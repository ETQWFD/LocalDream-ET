Nightmare Road-ET Server v1.6.17 (win64, 自带 CPU 推理引擎)
=========================================================
1. 双击 etserver.exe 启动（有界面 + 控制台日志）。
2. 引擎在 engine\（sd-server.exe + ggml 后端 DLL，MinGW 构建，不依赖 VC++ 运行库）。
   启动日志出现“引擎二进制: ...已就绪”即正常；界面状态会显示“正在后台加载模型…已用时 Ns”，
   模型加载完成后变“✅ 就绪 · 可出图”。
3. 模型放 models\，界面“切换已下载模型”选择；或在 config\etserver.conf 填 model_path。
4. 「校验 API Key」= 是否要求调用方带 Bearer 密钥（默认不校验）。
5. 「临时出租」= 按时长出租：自动生成一次性 Key、绑定端口，到期自动收回（rent_until 持久化）。
6. 若出图报 503，响应体会带 engine_missing / exit_code / loading_seconds，
   界面状态也会显示“引擎反复退出（0xXXXX：原因）”，据此定位（DLL/位数/CPU 指令/内存）。
7. 引擎参数可在 config 的 engine_args 追加，如 --type f16、--type q8_0。
