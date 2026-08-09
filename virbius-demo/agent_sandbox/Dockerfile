# Agent 工具执行的隔离容器。run_command 在此容器内执行（--network none）。
# 真实危险命令被困在一次性容器里，不触碰宿主。
FROM alpine:3.20
# 植入 RCE 关卡的旗标文件
RUN echo "FLAG{rce_v1a_ag3nt_t00l}" > /flag && \
    adduser -D -u 1000 agent
USER agent
WORKDIR /home/agent
CMD ["sh"]
