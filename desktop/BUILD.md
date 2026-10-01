# Local Dream ET — Windows 桌面版源码

C(Win32) 启动器 `launcher.c` + 官方 stable-diffusion.cpp(leejet, MIT) CPU 引擎(sd-cli.exe + ggml DLL)。

## 交叉编译（Linux）
```
x86_64-w64-mingw32-windres resource.rc -O coff -o resource.o
x86_64-w64-mingw32-gcc -O2 -municode -mwindows launcher.c resource.o -o LocalDream-ET.exe \
  -lcomctl32 -lshlwapi -lwininet -lole32 -loleaut32 -lgdi32 -luser32 -lshell32 -luuid -static-libgcc
```
把 sd-cli.exe 与全部 ggml*.dll（win-cpu-x64 release）与 LocalDream-ET.exe 放同一目录，
`makensis installer.nsi` 生成安装包。

模型/输出存于 `%LOCALAPPDATA%\LocalDreamET\{models,output}`；引擎在安装目录。
CLI 参数已对齐上游：-m/-p/-n/-W/-H/-s/-t/-o --steps --cfg-scale --sampling-method(euler_a|euler|dpm++2m)。
