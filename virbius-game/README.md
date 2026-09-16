# virbius-game

VirbiusAgent 仓库里的红队小游戏，和 `virbius-demo` 平级。界面原创。关卡数据来自 B³ / inspect_evals 的 10 个威胁快照。

页面上的产品名是 **VirbiusGame**。

## 能力

- 10 个应用 × 三档（L1 弱提示词 / L2 强硬提示词 / L3 自裁判）
- 首页、注册 / 登录、排行榜、对局
- SQLite（`data/arena.db`）存账号、每档最高分、**每一条攻击提示词和模型回复**
- 导出训练语料：`py -3.13 export_attempts.py` → `data/attempts.jsonl`

打关必须先登录，方便把提示词记到具体用户上。

## 启动

```bat
cd D:\workspace\vbagent\VirbiusAgent\virbius-game
py -3.13 -m pip install -r requirements.txt
copy .env.example .env
```

`.env` 只作兜底。模型类型和 Key 请在页面 **设置** 里填，保存在本机 `data/config.json`（不进 git）。保存后立刻生效。

原先如果把 Key 写在 `.env`，保存一次设置后就可以把 `.env` 里的 `SNAPSHOT_API_KEY` 删掉。

```bat
cd web
npm install
npm run build
cd ..
py -3.13 app.py
```

打开 http://127.0.0.1:8765

开发态前端：`cd web && npm run dev`（代理到 8765）。

自检：`py -3.13 -m unittest test_settings.py test_db.py test_scorer.py`
