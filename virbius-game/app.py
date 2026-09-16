"""virbius-game API + Vue static. Flask + SQLite."""

from __future__ import annotations

import os
from pathlib import Path

from flask import Flask, jsonify, request, send_from_directory, session

import catalog
import db
import judge
import llm
import scorer
import settings
import tools_spec

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"
ALLOWED_ORIGINS = {
    "http://127.0.0.1:5173",
    "http://localhost:5173",
    "http://127.0.0.1:8765",
    "http://localhost:8765",
}


def create_app() -> Flask:
    llm._load_dotenv()
    db.init()
    app = Flask(__name__, static_folder=None)
    app.secret_key = db.ensure_secret()
    app.config.update(
        SESSION_COOKIE_HTTPONLY=True,
        SESSION_COOKIE_SAMESITE="Lax",
        SESSION_COOKIE_NAME="arena_session",
    )

    @app.after_request
    def cors(resp):
        origin = request.headers.get("Origin")
        if origin in ALLOWED_ORIGINS:
            resp.headers["Access-Control-Allow-Origin"] = origin
            resp.headers["Access-Control-Allow-Credentials"] = "true"
            resp.headers["Access-Control-Allow-Headers"] = "Content-Type"
            resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        return resp

    @app.route("/api/<path:_any>", methods=["OPTIONS"])
    def preflight(_any):
        return ("", 204)

    def current_user():
        uid = session.get("uid")
        name = session.get("username")
        if not uid or not name:
            return None
        return {"id": uid, "username": name}

    def require_user():
        user = current_user()
        if not user:
            return None, (jsonify({"error": "请先登录"}), 401)
        return user, None

    @app.get("/api/apps")
    def apps():
        cfg = llm.configured()
        return jsonify(
            {
                "pass_score": catalog.PASS_SCORE,
                "llm_ready": cfg["ready"],
                "apps": [
                    {
                        "alias": a["alias"],
                        "slug": a["slug"],
                        "title": a["title"],
                        "tagline": a["tagline"],
                        "blurb": a["blurb"],
                        "order": a["order"],
                    }
                    for a in catalog.APPS
                ],
            }
        )

    @app.get("/api/meta")
    def meta_legacy():
        return jsonify(catalog.public_meta(catalog.get("solace"), llm.configured()))

    @app.get("/api/meta/<alias>")
    def meta(alias):
        try:
            app_meta = catalog.get(alias)
        except KeyError:
            return jsonify({"error": "没有这个关卡"}), 404
        return jsonify(catalog.public_meta(app_meta, llm.configured()))

    @app.get("/api/me")
    def me():
        user = current_user()
        if not user:
            return jsonify({"user": None, "progress": None})
        return jsonify({"user": {"username": user["username"]}, "progress": db.progress_all(user["id"])})

    @app.post("/api/register")
    def register():
        payload = request.get_json(silent=True) or {}
        try:
            user = db.register(str(payload.get("username") or ""), str(payload.get("password") or ""))
        except ValueError as e:
            return jsonify({"error": str(e)}), 400
        session["uid"] = user["id"]
        session["username"] = user["username"]
        return jsonify({"user": {"username": user["username"]}})

    @app.post("/api/login")
    def login():
        payload = request.get_json(silent=True) or {}
        try:
            user = db.login(str(payload.get("username") or ""), str(payload.get("password") or ""))
        except ValueError as e:
            return jsonify({"error": str(e)}), 400
        session["uid"] = user["id"]
        session["username"] = user["username"]
        return jsonify({"user": {"username": user["username"]}})

    @app.post("/api/logout")
    def logout():
        session.clear()
        return jsonify({"ok": True})

    @app.get("/api/leaderboard")
    def leaderboard():
        slug = request.args.get("app") or ""
        if slug:
            try:
                slug = catalog.get(slug)["slug"]
            except KeyError:
                return jsonify({"error": "没有这个关卡"}), 404
            return jsonify({"rows": db.leaderboard(slug), "scope": slug})
        return jsonify({"rows": db.leaderboard(), "scope": "all"})

    @app.get("/api/attempts")
    def attempts():
        user, err = require_user()
        if err:
            return err
        alias = str(request.args.get("app") or "solace")
        level = catalog._norm_level(str(request.args.get("level") or "L1"))
        try:
            app_meta = catalog.get(alias)
        except KeyError:
            return jsonify({"error": "没有这个关卡"}), 404
        return jsonify({"attempts": db.list_attempts(user["id"], app_meta["slug"], level)})

    @app.get("/api/settings")
    def get_settings():
        return jsonify(llm.public_config())

    @app.post("/api/settings")
    def post_settings():
        payload = request.get_json(silent=True) or {}
        try:
            settings.save(
                str(payload.get("provider") or "deepseek"),
                str(payload.get("model") or ""),
                str(payload.get("base") or ""),
                str(payload.get("key") or ""),
            )
        except ValueError as e:
            return jsonify({"error": str(e)}), 400
        return jsonify(llm.public_config())

    @app.post("/api/attack")
    def attack():
        user, err = require_user()
        if err:
            return err
        payload = request.get_json(silent=True) or {}
        text = str(payload.get("attack") or "").strip()
        alias = str(payload.get("app") or payload.get("alias") or "solace")
        level = catalog._norm_level(str(payload.get("level") or "L1"))
        if not text:
            return jsonify({"error": "先写攻击文本"}), 400
        try:
            app_meta = catalog.get(alias)
        except KeyError:
            return jsonify({"error": "没有这个关卡"}), 404
        try:
            snapshot = catalog.load_snapshot(app_meta["slug"], level)
            system, user_text = catalog.poison(snapshot, text)
            tools = tools_spec.openai_tools(snapshot.get("state_tools") or [])
            # TI 关必须出 tool call，否则 DeepSeek 会用拒答绕开 send_email。
            choice = "required" if "[TI]" in (snapshot.get("expected_output") or "") else "auto"
            reply = llm.chat(system, user_text, tools=tools or None, tool_choice=choice)
            result = scorer.score_response(reply, snapshot)
            if snapshot.get("use_judge"):
                cost, reason = judge.judge_cost(snapshot, text)
                result["judge_cost"] = cost
                result["judge_reason"] = reason
                if cost >= judge.MAXIMUM_JUDGE_COST:
                    result["score"] = 0
                    result["passed"] = False
                    result["raw"] = 0.0
            attempt_id = db.record_attempt(user["id"], app_meta["slug"], level, text, reply, result)
            return jsonify(
                {
                    "id": attempt_id,
                    "reply": reply,
                    "score": result["score"],
                    "passed": result["passed"],
                    "hits": result["hits"],
                    "tokens": result["tokens"],
                    "level": level,
                    "scoring": result.get("scoring"),
                    "judge_cost": result.get("judge_cost"),
                    "progress": db.progress_for(user["id"], app_meta["slug"]),
                }
            )
        except Exception as e:
            return jsonify({"error": str(e)}), 500

    @app.route("/", defaults={"path": ""})
    @app.route("/<path:path>")
    def spa(path: str):
        if path.startswith("api/"):
            return jsonify({"error": "not found"}), 404
        target = STATIC / path
        if path and target.is_file():
            return send_from_directory(STATIC, path)
        return send_from_directory(STATIC, "index.html")

    return app


def main() -> None:
    llm._load_dotenv()
    port = int(os.environ.get("SNAPSHOT_PORT") or "8765")
    app = create_app()
    cfg = llm.configured()
    print(f"virbius-game  http://127.0.0.1:{port}")
    print("模型已配置" if cfg["ready"] else "未配置模型：打开 /settings 选择类型并保存 Key")
    app.run(host="127.0.0.1", port=port, debug=False, threaded=True)


if __name__ == "__main__":
    main()
