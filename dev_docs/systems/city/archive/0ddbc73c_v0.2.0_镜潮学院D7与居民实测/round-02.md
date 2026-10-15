# 第二轮：地表规划通过，方块状态激活校验失败

实际调用：run/codex_logs/campus-d7-resume-02.json、campus-d7-status-02.json；客户端 call-0004 起。

复用 D3/D4/D5/D6 成功，city_plan_land_use success，city_plan_city_walls success，city_execute_city_walls activated。city_execute_d5 返回 CITY_LAND_USE_SURFACE_BLOCK_UNKNOWN，具体为 AUTHORED_BLOCK minecraft:birch_leaves[distance=1,persistent=true,waterlogged=false]，来源 public_realm::fringe/trees/76@studio:mg-50-v01。

这是有效调用的程序功能失败，本轮结束。目标建筑区块尚未访问；城墙 registry 已激活，后续不得丢失该有效上游。保存退出后核对作者方块状态的运行时校验与执行契约，修复后继续同一副本。
