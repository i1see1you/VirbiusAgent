# -*- coding: utf-8 -*-
"""聚合清单：收录优秀大模型攻防靶场仓库（含活跃状态）。"""
import json
import os
from flask import Blueprint, render_template

bp = Blueprint("catalog", __name__, url_prefix="/catalog")

_PATH = os.path.join(os.path.dirname(__file__), "..", "data", "catalog.json")


@bp.route("/")
def index():
    with open(_PATH, encoding="utf-8") as f:
        data = json.load(f)
    return render_template("catalog.html", data=data)
