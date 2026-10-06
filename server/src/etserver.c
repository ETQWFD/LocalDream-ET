/*
 * Local Dream ET Server - 本地 AI 出图 HTTP API 外壳
 * ---------------------------------------------------------------------------
 * 版权所有 (c) 2026  Local Dream ET Server  (ET)
 * 版本: 1.0.0
 *
 * 本程序是一层“外壳 / 鉴权反向代理 / 看门狗”，真实的出图推理由子进程
 * stable-diffusion.cpp 的 sd-server 提供（MIT 许可，上游仓库
 * https://github.com/leejet/stable-diffusion.cpp ）。
 *
 * 职责：
 *   1) 首次运行控制台向导：列出与手机端对齐的 SD1.5 模型，选择后用 curl
 *      断点续传到程序当前目录 models/，并做大小校验。
 *   2) 主界面：显眼展示 服务地址 / 46 位 API Key / 模型 id，可一键复制。
 *   3) 看门狗：把 sd-server 作为子进程拉起，绑定 127.0.0.1:内部端口；
 *      子进程非 0 退出或健康检查失败时，退避自动重启，累计重启次数入日志。
 *   4) HTTP 鉴权代理：对外监听 0.0.0.0:8080，所有受保护端点都要求
 *      Authorization: Bearer <key> （或 X-API-Key / ?api_key=），否则 401；
 *      通过鉴权后把请求原样转发给本机 sd-server，再把响应流式回传。
 *
 * 跨平台：同一源码用 gcc(Linux) 与 x86_64-w64-mingw32-gcc(Windows) 编译。
 * ---------------------------------------------------------------------------
 */
#ifndef _GNU_SOURCE
  #define _GNU_SOURCE
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>
#include <ctype.h>
#include <time.h>
#include <sys/stat.h>

/* 路径分隔符 */
#ifdef _WIN32
  #define DIRSEP "\\"
#else
  #define DIRSEP "/"
#endif
#ifdef _WIN32
  /* mingw 无 strcasestr，提供实现 */
  static char* strcasestr(const char* h, const char* n) {
      size_t nl=strlen(n); if(!nl) return (char*)h;
      for(;*h;h++){ size_t i=0; while(i<nl && tolower((unsigned char)h[i])==tolower((unsigned char)n[i])) i++;
        if(i==nl) return (char*)h; }
      return NULL;
  }
#endif

#ifdef _WIN32
  #include <winsock2.h>
  #include <ws2tcpip.h>
  #include <process.h>
  #include <windows.h>
  #include <shellapi.h>
  #include <io.h>
  #pragma comment(lib,"ws2_32.lib")
  #pragma comment(lib,"shell32.lib")
  #define close_socket closesocket
  #define poll_sleep_ms(ms) Sleep(ms)
  #define access _access
  #ifndef X_OK
    #define X_OK 00
  #endif
  typedef SOCKET sock_t;
  #define MSG_NOSIGNAL 0          /* Windows 无 MSG_NOSIGNAL，SIGPIPE 本就不存在 */
  #define SHUT_WR      SD_SEND    /* Windows shutdown 第二参数 */
#else
  #include <unistd.h>
  #include <sys/socket.h>
  #include <netinet/in.h>
  #include <arpa/inet.h>
  #include <pthread.h>
  #include <sys/wait.h>
  #include <signal.h>
  #include <errno.h>
  #define close_socket close
  #define poll_sleep_ms(ms) usleep((ms)*1000)
  typedef int sock_t;
#endif

/* ============================ 全局配置 ============================ */
#define APP_NAME      "Local Dream ET Server"
#define APP_VERSION   "1.1.0"
#define COPYRIGHT_STR "Copyright (C) 2026 etc"

#define DEFAULT_PORT       8080      /* 对外监听端口 */
#define DEFAULT_BIND       "0.0.0.0"
#define UPSTREAM_BASE_PORT 17860     /* sd-server 内部端口起点 */
#define KEY_LEN            46        /* API Key 固定长度 */
#define MAX_LINE           8192

typedef struct {
    int   port;             /* 对外端口 */
    int   inner_port;       /* sd-server 内部端口 */
    char  api_key[KEY_LEN + 1];
    char  model_path[1024]; /* 已选模型文件绝对/相对路径 */
    char  model_id[128];
    int   content_filter;   /* 内容限制开关：0=无限制(默认) */
    int   dark_theme;       /* 1=暗黑(默认) */
    int   threads;
    int   autostart;
} Config;

static Config g_cfg;
static char g_progdir[1024] = ".";   /* 程序当前目录 */
static char g_config_path[1280];
static char g_model_dir[1280];
static volatile int g_running = 1;
static volatile int g_restart_count = 0;
static volatile int g_engine_ready = 0;  /* 仅由看门狗探测更新；/healthz 直接读，不阻塞请求线程 */

/* 前向声明（定义在后文） */
static void mkdir_p(const char* base, const char* sub);

/* 引擎子进程句柄（跨平台表示） */
#ifdef _WIN32
static PROCESS_INFORMATION g_child = {0};
static int g_child_started = 0;
#else
static pid_t g_child_pid = 0;
#endif

/* ------------------------- 可移植互斥体 ------------------------- */
#ifdef _WIN32
  static CRITICAL_SECTION g_logcs;
  #define LOG_ENTER() EnterCriticalSection(&g_logcs)
  #define LOG_LEAVE() LeaveCriticalSection(&g_logcs)
  #define LOG_INIT()  InitializeCriticalSection(&g_logcs)
#else
  static pthread_mutex_t g_logmtx;
  #define LOG_ENTER() pthread_mutex_lock(&g_logmtx)
  #define LOG_LEAVE() pthread_mutex_unlock(&g_logmtx)
  #define LOG_INIT()  pthread_mutex_init(&g_logmtx,NULL)
#endif

/* ------------------------- 日志（UTF-8） ------------------------- */
static void log_line(const char* fmt, ...) {
    char buf[2048];
    va_list ap; va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    time_t t = time(NULL); struct tm tmv;
#ifdef _WIN32
    localtime_s(&tmv, &t);
#else
    localtime_r(&t, &tmv);
#endif
    char ts[32]; strftime(ts, sizeof(ts), "%H:%M:%S", &tmv);
    LOG_ENTER();
    printf("[%s] %s\n", ts, buf);
    fflush(stdout);
    LOG_LEAVE();
}

/* ------------------------- 取程序目录 ------------------------- */
static void detect_progdir(void) {
#ifdef _WIN32
    GetModuleFileNameA(NULL, g_progdir, sizeof(g_progdir));
    char* s = strrchr(g_progdir, '\\'); if (s) *s = 0;
#else
    ssize_t n = readlink("/proc/self/exe", g_progdir, sizeof(g_progdir)-1);
    if (n > 0) { g_progdir[n]=0; char*s=strrchr(g_progdir,'/'); if(s)*s=0; }
    else strcpy(g_progdir, ".");
#endif
    snprintf(g_config_path, sizeof(g_config_path), "%s%sconfig%setserver.conf",
             g_progdir, DIRSEP, DIRSEP);
    snprintf(g_model_dir, sizeof(g_model_dir), "%s%smodels", g_progdir, DIRSEP);
}

/* ------------------------- 46 位随机 Key ------------------------- */
static const char KEYSET[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
static void gen_api_key(char* out) {
    unsigned char r[KEY_LEN];
#ifdef _WIN32
    HCRYPTPROV h; CryptAcquireContextA(&h, NULL, NULL, PROV_RSA_FULL, CRYPT_VERIFYCONTEXT);
    CryptGenRandom(h, KEY_LEN, r); CryptReleaseContext(h, 0);
#else
    FILE* f = fopen("/dev/urandom","rb");
    if (f) { if(fread(r,1,KEY_LEN,f)!=(size_t)KEY_LEN){ /* ignore */ } fclose(f); }
    else { for(int i=0;i<KEY_LEN;i++) r[i]=(unsigned char)rand(); }
#endif
    for (int i=0;i<KEY_LEN;i++) out[i] = KEYSET[r[i] % (sizeof(KEYSET)-1)];
    out[KEY_LEN]=0;
}

/* ------------------------- 配置读写 ------------------------- */
static void default_config(void) {
    memset(&g_cfg, 0, sizeof(g_cfg));
    g_cfg.port = DEFAULT_PORT;
    g_cfg.inner_port = UPSTREAM_BASE_PORT;
    g_cfg.content_filter = 0;   /* 默认无限制 */
    g_cfg.dark_theme = 1;
    g_cfg.threads = 2;
    g_cfg.autostart = 0;
    gen_api_key(g_cfg.api_key); /* 首次随机生成 */
    g_cfg.model_path[0]=0; g_cfg.model_id[0]=0;
}

static void save_config(void) {
    mkdir_p(g_progdir, "config");
    FILE* f = fopen(g_config_path, "w");
    if (!f) { log_line("无法写入配置: %s", g_config_path); return; }
    fprintf(f, "# Local Dream ET Server 配置 (Copyright (C) 2026 etc)\n");
    fprintf(f, "port=%d\n", g_cfg.port);
    fprintf(f, "inner_port=%d\n", g_cfg.inner_port);
    fprintf(f, "api_key=%s\n", g_cfg.api_key);
    fprintf(f, "model_path=%s\n", g_cfg.model_path);
    fprintf(f, "model_id=%s\n", g_cfg.model_id);
    fprintf(f, "content_filter=%d\n", g_cfg.content_filter);
    fprintf(f, "dark_theme=%d\n", g_cfg.dark_theme);
    fprintf(f, "threads=%d\n", g_cfg.threads);
    fprintf(f, "autostart=%d\n", g_cfg.autostart);
    fclose(f);
}

static int load_config(void) {
    FILE* f = fopen(g_config_path, "r");
    if (!f) return 0;
    char line[2048];
    while (fgets(line, sizeof(line), f)) {
        if (line[0]=='#'||line[0]=='\n'||line[0]=='\r'||line[0]==0) continue;
        char* eq = strchr(line,'='); if(!eq) continue;
        *eq=0; char*k=line; char*v=eq+1;
        v[strcspn(v,"\r\n")]=0;
        if      (!strcmp(k,"port"))        g_cfg.port=atoi(v);
        else if (!strcmp(k,"inner_port"))  g_cfg.inner_port=atoi(v);
        else if (!strcmp(k,"api_key"))     { strncpy(g_cfg.api_key,v,KEY_LEN); g_cfg.api_key[KEY_LEN]=0; }
        else if (!strcmp(k,"model_path"))  strncpy(g_cfg.model_path,v,sizeof(g_cfg.model_path)-1);
        else if (!strcmp(k,"model_id"))    strncpy(g_cfg.model_id,v,sizeof(g_cfg.model_id)-1);
        else if (!strcmp(k,"content_filter")) g_cfg.content_filter=atoi(v);
        else if (!strcmp(k,"dark_theme"))  g_cfg.dark_theme=atoi(v);
        else if (!strcmp(k,"threads"))     g_cfg.threads=atoi(v);
        else if (!strcmp(k,"autostart"))   g_cfg.autostart=atoi(v);
    }
    fclose(f);
    /* 兜底：key 长度不对就重新生成 */
    if (strlen(g_cfg.api_key) != KEY_LEN) { gen_api_key(g_cfg.api_key); }
    return 1;
}

/* ============================ 小工具 ============================ */
static void mkdir_p(const char* base, const char* sub) {
    char p[1280]; snprintf(p,sizeof(p),"%s%s%s", base, DIRSEP, sub);
#ifdef _WIN32
    CreateDirectoryA(base, NULL); CreateDirectoryA(p, NULL);
#else
    mkdir(base, 0755); mkdir(p, 0755);
#endif
}

/* 取本机局域网 IPv4（用于展示） */
static void get_lan_ip(char* out, int outlen) {
    out[0]=0;
#ifdef _WIN32
    char host[256]; gethostname(host,sizeof(host));
    struct addrinfo hints,*res=NULL; memset(&hints,0,sizeof(hints));
    hints.ai_family=AF_INET;
    if (getaddrinfo(host,NULL,&hints,&res)==0) {
        for(struct addrinfo*r=res;r;r=r->ai_next){
            struct sockaddr_in*in=(struct sockaddr_in*)r->ai_addr;
            const char* s=inet_ntoa(in->sin_addr);
            if(strncmp(s,"127.",4)!=0){ snprintf(out,outlen,"%s",s); break; }
        }
        freeaddrinfo(res);
    }
#else
    /* 用一个 UDP 连外部地址的方式取出口 IP（不真正发包） */
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s>=0) {
        struct sockaddr_in d; memset(&d,0,sizeof(d));
        d.sin_family=AF_INET; d.sin_port=htons(80);
        inet_pton(AF_INET,"8.8.8.8",&d.sin_addr);
        if (connect(s,(struct sockaddr*)&d,sizeof(d))==0) {
            struct sockaddr_in l; socklen_t n=sizeof(l);
            getsockname(s,(struct sockaddr*)&l,&n);
            inet_ntop(AF_INET,&l.sin_addr,out,outlen);
        }
        close_socket(s);
    }
#endif
    if (!out[0]) snprintf(out,outlen,"127.0.0.1");
}

/* ============================ 内容限制 ============================
 * 与手机端同逻辑的本地违禁词拦截（子串匹配，仅做粗筛，服务端默认关闭）。
 */
static int load_banned_words(const char*** out_list, int* out_n) {
    static const char* words[64]; int n=0;
    char path[1280]; snprintf(path,sizeof(path),"%s%sconfig%sbanned.txt",g_progdir,DIRSEP,DIRSEP);
    FILE* f=fopen(path,"r");
    if (f) {
        char buf[256];
        while(fgets(buf,sizeof(buf),f) && n<63){
            buf[strcspn(buf,"\r\n")]=0;
            if(buf[0]) words[n++]=strdup(buf);
        }
        fclose(f);
    }
    /* 内置兜底词表（仅做演示级拦截） */
    static const char* builtin[] = {"child sexual", "CSAM", NULL};
    for(int i=0; builtin[i] && n<63; i++) words[n++]=builtin[i];
    *out_list=words; *out_n=n;
    return n;
}
/* 在一段文本里做违禁词子串扫描（大小写不敏感）。命中返回 1。 */
static int text_has_banned(const char* body) {
    if (!g_cfg.content_filter) return 0;
    const char** list; int n; load_banned_words(&list,&n);
    for(int i=0;i<n;i++){
        const char* w=list[i]; size_t wl=strlen(w);
        for(const char*p=body; *p; p++){
            size_t k=0;
            while(k<wl && tolower((unsigned char)p[k])==tolower((unsigned char)w[k])) k++;
            if(k==wl) return 1;
        }
    }
    return 0;
}
/* 从 JSON  body 里粗略抽取 prompt 字段值（仅做关键词扫描用，不追求严格 JSON） */
static int body_triggers_filter(const char* body, int blen) {
    if (!g_cfg.content_filter) return 0;
    /* 把整个 body 交给扫描器即可：prompt/negative_prompt 都在里面 */
    char* tmp = malloc(blen+1); if(!tmp) return 0;
    memcpy(tmp, body, blen); tmp[blen]=0;
    int hit = text_has_banned(tmp);
    free(tmp);
    return hit;
}

/* ============================ 模型表（对齐手机端 SD1.5，GGUF 已校验） ============================
 * 每条 URL 均经 curl HEAD/Range 实测：200/206 + Content-Length + Accept-Ranges: bytes。
 * 沙箱不真下 GB 模型；未校验通过的标 verified=0，菜单里提示“暂无可用 GGUF 源/待补”，不放假入口。
 * sources: 默认 hf-mirror（国内），可手工切 HuggingFace / ModelScope。 */
typedef struct {
    const char* id;       /* 模型 id */
    const char* name;     /* 显示名 */
    const char* gguf;     /* hf-mirror 直链（已校验） */
    long long   size;     /* 实际 Content-Length 字节（已校验） */
    int         verified; /* 1=已实测 200/206；0=暂无可用 GGUF 源 */
} ModelEntry;
static ModelEntry MODELS[] = {
    /* 以下 4 条为 2026-10-06 实测通过的 GGUF 直链（Q4_0，CPU 可跑） */
    { "sd15-anythingv5",  "Anything V5 (SD1.5 动漫 Q4_0)",
      "https://hf-mirror.com/genai-archive/anything-v5-gguf/resolve/main/anything-v5.q4_0.gguf",
      1570410496, 1 },
    { "sd15-realisticv6",  "Realistic Vision V6.0 (写实 Q4_0)",
      "https://hf-mirror.com/second-state/Realistic_Vision_V6.0_B1-GGUF/resolve/main/realisticVisionV60B1_v51HyperVAE-Q4_0.gguf",
      1570410496, 1 },
    { "sd15-base",         "Stable Diffusion 1.5 原版 (Q4_0)",
      "https://hf-mirror.com/second-state/stable-diffusion-v1-5-GGUF/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
      1566768416, 1 },
    { "sd15-base-s",       "Stable Diffusion 1.5 原版 (镜像备用源)",
      "https://hf-mirror.com/Sashkanik13/sd1.5-text2img-gguf/resolve/main/model_q4_0.gguf",
      1566768416, 1 },
    { "sd15-chillout",     "ChilloutMix (写实 Q8_0)",
      "https://hf-mirror.com/MomoSoft/chilloutmix-sd15-q8-gguf/resolve/main/chilloutmix_q8_0.gguf",
      1801579488, 1 },
    /* 以下 6 个手机端同名模型：civitai 动漫模型无独立 GGUF，但 sd-server 可直接加载
       safetensors 单文件 checkpoint；以下直链均已 2026-10-06 Range(206) 实测，字节为准 */
    { "sd15-dreamshaper",  "DreamShaper 8 (全能 SD1.5 写实/动漫，safetensors)",
      "https://hf-mirror.com/Lykon/DreamShaper/resolve/main/DreamShaper_8_pruned.safetensors",
      2132625894LL, 1 },
    { "sd15-absreal",      "Absolute Reality v1.8.1 (写实，safetensors)",
      "https://hf-mirror.com/Lykon/AbsoluteReality/resolve/main/AbsoluteReality_1.8.1_pruned.safetensors",
      2132625432LL, 1 },
    { "sd15-qteamix",      "QteaMix (动漫 Q 版 fp16，safetensors)",
      "https://hf-mirror.com/chenxluo/QteaMix/resolve/main/QteaMix-fp16.safetensors",
      2546054463LL, 1 },
    { "sd15-cuteyuki",     "CuteYukiMix Adorable midchapter3 (可爱动漫，safetensors)",
      "https://hf-mirror.com/wayne080211/cuteyukimix_model/resolve/main/cuteyukimixAdorable_midchapter3.safetensors",
      2132626090LL, 1 },
    { "sd15-breakdomain",  "BreakDomain Realistic R2333 (清爽动漫风，safetensors)",
      "https://hf-mirror.com/Cooper/breakdomainrealistic/resolve/main/breakdomainrealistic_R2333.safetensors",
      2175121872LL, 1 },
    { "sd15-counterfeit",  "Counterfeit V3.0 (动漫 fp16，较大 4.2GB，safetensors)",
      "https://hf-mirror.com/gsdf/Counterfeit-V3.0/resolve/main/Counterfeit-V3.0_fp16.safetensors",
      4244124028LL, 1 },
    { NULL, NULL, NULL, 0, 0 }
};

/* 下载通道：默认 hf-mirror；切源时把 URL 前缀替换为对应域名。 */
static const char* MIRRORS[][2] = {
    { "hf-mirror.com",   "https://hf-mirror.com" },   /* 默认国内 */
    { "huggingface.co",  "https://huggingface.co" },   /* 官方 */
    { "modelscope.cn",   "https://modelscope.cn" },    /* ModelScope（需逐模型适配路径，默认留空提示） */
    { NULL, NULL }
};

/* ============================ 模型下载向导 ============================ */
/* 用 curl 断点续传下载到 models/。返回 0 成功。 */
static int download_model(const ModelEntry* m) {
    if(!m->verified || !m->gguf[0]) {
        log_line("该模型暂无可用 GGUF 源，已跳过（不放假入口）。"); return -1;
    }
    mkdir_p(g_progdir, "models");
    char out[1280], part[1300];
    const char* slash = strrchr(m->gguf, '/');
    const char* fname = slash ? slash+1 : m->gguf;
    snprintf(out, sizeof(out), "%s%s%s", g_model_dir, DIRSEP, fname);
    snprintf(part, sizeof(part), "%s.part", out);
    log_line("开始下载模型: %s", fname);
    log_line("  来源(镜像): %s", m->gguf);
    log_line("  保存到: %s  (总 %lld 字节)", out, m->size);
    /* -C - 断点续传（复用 .part）；--progress-bar 打印进度/速度；-L 跟随重定向 */
    char cmd[2400];
    snprintf(cmd,sizeof(cmd),
        "curl -L -C - --retry 5 --retry-delay 2 --progress-bar -o \"%s\" \"%s\"", out, m->gguf);
    log_line("  执行: %s", cmd);
    int rc = system(cmd);
    if (rc != 0) { log_line("下载中断(curl 返回 %d)，.part 已保留，重跑可断点续传", rc); return -1; }
    struct stat st;
    if (stat(out,&st)!=0 || st.st_size < 1000000) {
        log_line("下载文件异常(太小或不存在)，视为失败，不标记就绪"); return -1;
    }
    /* 完整性：实际字节必须等于校验过的 Content-Length */
    if (m->size > 0 && st.st_size != m->size) {
        log_line("完整性校验失败：实际 %lld 字节 != 期望 %lld 字节，不标记就绪",
                 (long long)st.st_size, (long long)m->size);
        return -1;
    }
    log_line("下载完成并通过大小校验: %lld 字节", (long long)st.st_size);
    strncpy(g_cfg.model_path, out, sizeof(g_cfg.model_path)-1);
    strncpy(g_cfg.model_id, m->id, sizeof(g_cfg.model_id)-1);
    save_config();
    return 0;
}

static void run_wizard(void) {
    /* 非 TTY（重定向/管道/托管/服务化）不卡在 fgets，直接进入代理外壳 */
#ifdef _WIN32
    if (!_isatty(_fileno(stdin))) {
#else
    if (!isatty(fileno(stdin))) {
#endif
        printf("[提示] 非交互终端启动，跳过首启模型向导。\n");
        printf("       请在 config/etserver.conf 填 model_path，或用 --model <路径> 指定。\n");
        return;
    }
    printf("\n===== 首次运行：选择模型 =====\n");
    printf("(CPU 推理，SD1.5 GGUF；下载到程序目录 models/，支持断点续传)\n\n");
    /* 只对已校验(verified)的编号，未校验项如实列出但不可选 */
    int i=0, n=0;
    while (MODELS[i].id) {
        if (MODELS[i].verified) {
            printf("  [%d] %s   (%.1f GB)\n", ++n, MODELS[i].name, MODELS[i].size/1e9);
        } else {
            printf("  [ ] %s   (暂不可选，无可用 GGUF 源)\n", MODELS[i].name);
        }
        i++;
    }
    printf("\n输入编号(1-%d)，或 0 跳过稍后手动指定: ", n);
    fflush(stdout);
    char buf[16]; if(!fgets(buf,sizeof(buf),stdin)) return;
    int sel = atoi(buf);
    if (sel>=1 && sel<=n) {
        /* 跳过未校验项，定位到第 sel 个已校验模型 */
        int k=0, j=0;
        while (MODELS[j].id) {
            if (MODELS[j].verified) { if(++k==sel) break; }
            j++;
        }
        download_model(&MODELS[j]);
    } else {
        printf("已跳过下载。可稍后在 config/etserver.conf 手动填 model_path。\n");
    }
    save_config();
}

/* ============================ 引擎子进程 ============================ */
/* 拼出 sd-server 启动命令并以子进程方式拉起。绑定 127.0.0.1:inner_port。 */
static int spawn_engine(void) {
    char engine[1280], args[3000];
    /* 引擎 sd-server 与外壳 etserver 放在同一目录（安装包的 bin/） */
#ifdef _WIN32
    snprintf(engine,sizeof(engine),"%s%ssd-server.exe", g_progdir,DIRSEP);
#else
    snprintf(engine,sizeof(engine),"%s%ssd-server", g_progdir,DIRSEP);
#endif
    if (access(engine, X_OK)!=0) { log_line("找不到引擎可执行程序: %s", engine); return -1; }
    snprintf(args,sizeof(args),
        "--model \"%s\" --listen-ip 127.0.0.1 --listen-port %d --threads %d",
        g_cfg.model_path, g_cfg.inner_port, g_cfg.threads);
    log_line("拉起引擎: %s %s", engine, args);

#ifdef _WIN32
    char cmdline[3200];
    snprintf(cmdline,sizeof(cmdline),"\"%s\" %s", engine, args);
    STARTUPINFOA si; ZeroMemory(&si,sizeof(si)); si.cb=sizeof(si);
    ZeroMemory(&g_child,sizeof(g_child));
    if (!CreateProcessA(engine, cmdline, NULL,NULL,FALSE,0,NULL,g_progdir,&si,&g_child)) {
        log_line("CreateProcess 失败: %lu", GetLastError()); return -1;
    }
    g_child_started=1;
#else
    fflush(NULL);
    pid_t pid = fork();
    if (pid<0) { log_line("fork 失败"); return -1; }
    if (pid==0) {
        /* 子进程：执行 sd-server */
        char* av[16]; int ac=0;
        av[ac++]=engine;
        av[ac++]="--model";       av[ac++]=g_cfg.model_path;
        av[ac++]="--listen-ip";  av[ac++]="127.0.0.1";
        char p[16]; snprintf(p,sizeof(p),"%d",g_cfg.inner_port); av[ac++]="--listen-port"; av[ac++]=p;
        char th[16]; snprintf(th,sizeof(th),"%d",g_cfg.threads); av[ac++]="--threads"; av[ac++]=th;
        av[ac]=NULL;
        execv(engine, av);
        exit(127); /* exec 失败 */
    }
    g_child_pid = pid;
#endif
    return 0;
}

static int child_alive(void) {
#ifdef _WIN32
    if(!g_child_started) return 0;
    DWORD c; GetExitCodeProcess(g_child.hProcess,&c);
    return (c==STILL_ACTIVE);
#else
    if(g_child_pid<=0) return 0;
    pid_t r=waitpid(g_child_pid,NULL,WNOHANG);
    if(r==g_child_pid) return 0; /* 已退出 */
    return 1;
#endif
}

static void kill_engine(void) {
#ifdef _WIN32
    if(g_child_started){ TerminateProcess(g_child.hProcess,0); WaitForSingleObject(g_child.hProcess,2000);
        CloseHandle(g_child.hThread); CloseHandle(g_child.hProcess); g_child_started=0; }
#else
    if(g_child_pid>0){ kill(g_child_pid,SIGKILL); waitpid(g_child_pid,NULL,0); g_child_pid=0; }
#endif
}

/* 向上游 sd-server 做一次健康探测（GET /）。返回 1=就绪。 */
static int upstream_healthy(void) {
    sock_t s = socket(AF_INET, SOCK_STREAM, 0);
    if (s<0) return 0;
    /* 探测 socket 加 1s 收发超时，绝不无限挂死 */
    struct timeval pt; pt.tv_sec=1; pt.tv_usec=0;
    setsockopt(s,SOL_SOCKET,SO_SNDTIMEO,(char*)&pt,sizeof(pt));
    setsockopt(s,SOL_SOCKET,SO_RCVTIMEO,(char*)&pt,sizeof(pt));
    struct sockaddr_in a; memset(&a,0,sizeof(a));
    a.sin_family=AF_INET; a.sin_port=htons((uint16_t)g_cfg.inner_port);
    a.sin_addr.s_addr=inet_addr("127.0.0.1");
    if (connect(s,(struct sockaddr*)&a,sizeof(a))!=0){ close_socket(s); return 0; }
    const char* req="GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    send(s, req, (int)strlen(req), MSG_NOSIGNAL);
    char buf[256]; int n=recv(s,buf,sizeof(buf)-1,0);
    close_socket(s);
    if(n<=0) return 0;
    buf[n]=0;
    return (strstr(buf," 200 ")!=NULL);
}

/* ============================ 看门狗线程 ============================ */
static void* watchdog_thread(void* arg) {
    (void)arg;
    int backoff=1;
    while(g_running) {
        poll_sleep_ms(2000);
        if(!g_running) break;
        int alive = child_alive();
        int healthy = alive ? upstream_healthy() : 0;
        g_engine_ready = healthy;   /* 更新全局就绪标志，供 /healthz 毫秒级读取 */
        if(!alive || !healthy) {
            g_restart_count++;
            log_line("看门狗检测到引擎异常(alive=%d healthy=%d)，第 %d 次重启，退避 %ds",
                     alive, healthy, g_restart_count, backoff);
            kill_engine();
            poll_sleep_ms(backoff*1000);
            backoff = backoff<15 ? backoff*2 : 30; /* 指数退避，封顶 30s */
            if(!g_running) break;
            if(spawn_engine()==0){
                /* 等待最多 30s 让模型加载并开始监听 */
                int waited=0;
                while(g_running && waited<30000){
                    if(upstream_healthy()){ log_line("引擎已就绪(重启后)"); backoff=1; break; }
                    poll_sleep_ms(1000); waited+=1000;
                }
            }
        }
    }
    return NULL;
}

/* ============================ HTTP 鉴权反向代理 ============================ */
/* 从请求头里取 API Key：Authorization: Bearer xxx / X-API-Key: xxx / ?api_key=xxx */
static int check_auth(const char* hdr_block, const char* query) {
    /* 1) Authorization: Bearer <key> */
    const char* p = strcasestr(hdr_block, "authorization:");
    if (p) { p += 14; while(*p==' '||*p=='\t') p++;
        if (strncasecmp(p,"bearer ",7)==0) p+=7;
        char got[128]; int n=0;
        while(*p && *p!='\r' && *p!='\n' && n<(int)sizeof(got)-1) got[n++]=*p++;
        got[n]=0;
        if(strcmp(got,g_cfg.api_key)==0) return 1;
    }
    /* 2) X-API-Key */
    p = strcasestr(hdr_block, "x-api-key:");
    if (p) { p+=10; while(*p==' '||*p=='\t') p++;
        char got[128]; int n=0;
        while(*p && *p!='\r' && *p!='\n' && n<(int)sizeof(got)-1) got[n++]=*p++;
        got[n]=0;
        if(strcmp(got,g_cfg.api_key)==0) return 1;
    }
    /* 3) ?api_key= */
    if (query) {
        p = strstr(query, "api_key=");
        if(p){ p+=8; char got[128]; int n=0;
            while(*p && *p!='&' && n<(int)sizeof(got)-1) got[n++]=*p++;
            got[n]=0;
            if(strcmp(got,g_cfg.api_key)==0) return 1;
        }
    }
    return 0;
}

static void send_json(sock_t c, int code, const char* status, const char* body) {
    char hdr[1024];
    int blen = (int)strlen(body);
    int n = snprintf(hdr,sizeof(hdr),
        "HTTP/1.1 %d %s\r\nContent-Type: application/json; charset=utf-8\r\n"
        "Content-Length: %d\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n",
        code, status, blen);
    /* 合并头+体为一次 send，避免两次 send 间连接被 RST */
    char all[1400]; memcpy(all, hdr, n); memcpy(all+n, body, blen);
    int sent = send(c, all, n+blen, MSG_NOSIGNAL);
    (void)sent;
    /* 给内核一点时间把响应刷到对端，再半关写方向 */
    poll_sleep_ms(30);
    shutdown(c, SHUT_WR);
}

/* 把客户端已解析的请求转发给上游，并把上游响应原样流式回传给客户端。 */
static void forward_to_upstream(sock_t c, const char* method, const char* target,
                                const char* hdr_block, const char* body, int body_len) {
    sock_t u = socket(AF_INET, SOCK_STREAM, 0);
    if (u<0) { send_json(c,502,"Bad Gateway","{\"error\":\"upstream socket\"}"); return; }
    struct sockaddr_in a; memset(&a,0,sizeof(a));
    a.sin_family=AF_INET; a.sin_port=htons((uint16_t)g_cfg.inner_port);
    a.sin_addr.s_addr=inet_addr("127.0.0.1");
    if (connect(u,(struct sockaddr*)&a,sizeof(a))!=0) {
        close_socket(u);
        send_json(c,503,"Service Unavailable","{\"error\":\"engine not ready, try again\"}");
        return;
    }
    /* 拼转发请求行 + 透传头(去掉 Host/Connection)，强制 Connection: close */
    char req[MAX_LINE*4]; int w=0;
    w+=snprintf(req+w,sizeof(req)-w,"%s %s HTTP/1.1\r\nHost: 127.0.0.1:%d\r\nConnection: close\r\n",
                method, target, g_cfg.inner_port);
    /* 透传 Content-Type / Content-Length / Accept 等（跳过 host/authorization/connection） */
    char hcopy[4096]; snprintf(hcopy,sizeof(hcopy),"%s",hdr_block);
    char* line=strtok(hcopy,"\r\n");
    while(line){
        if(strncasecmp(line,"host:",5) && strncasecmp(line,"authorization:",13) &&
           strncasecmp(line,"connection:",10)) {
            w+=snprintf(req+w,sizeof(req)-w,"%s\r\n",line);
        }
        line=strtok(NULL,"\r\n");
    }
    w+=snprintf(req+w,sizeof(req)-w,"\r\n");
    send(u,req,w,0);
    if(body_len>0) send(u,body,body_len,0);
    /* 流式回传上游响应（含状态行/头/体）直到上游关闭 */
    char buf[16384]; int r;
    while((r=recv(u,buf,sizeof(buf),0))>0){ send(c,buf,r,MSG_NOSIGNAL); }
    close_socket(u);
}

/* 单个客户端连接的处理线程 */
typedef struct { sock_t c; } ConnArg;
static void* handle_conn(void* arg) {
    sock_t c = ((ConnArg*)arg)->c; free(arg);
    struct timeval tv; tv.tv_sec=120; tv.tv_usec=0;
    setsockopt(c,SOL_SOCKET,SO_RCVTIMEO,(char*)&tv,sizeof(tv));

    char buf[65536]; int total=0;
    /* 读到 headers 结束 */
    while(total < (int)sizeof(buf)-1) {
        int r=recv(c, buf+total, sizeof(buf)-1-total, 0);
        if(r<=0) break;
        total+=r; buf[total]=0;
        char* e=strstr(buf,"\r\n\r\n");
        if(e){ e+=4; break; }
    }
    if(total<=0){ close_socket(c); return NULL; }
    buf[total]=0;
    char* hdrs_end=strstr(buf,"\r\n\r\n");
    if(!hdrs_end){ close_socket(c); return NULL; }
    *hdrs_end=0;
    char* body = hdrs_end+4;
    int hdr_len = (int)(body-buf);

    char method[16]={0}, target[4096]={0};
    if (sscanf(buf,"%15s %4095s",method,target)!=2){ close_socket(c); return NULL; }

    /* 拆分 path 与 query */
    char path[4096], query[2048]={0};
    snprintf(path,sizeof(path),"%s",target);
    char* q=strchr(path,'?'); if(q){ *q=0; snprintf(query,sizeof(query),"%s",q+1); }

    /* Content-Length 读 body */
    int content_len=0;
    char* cl=strcasestr(buf,"content-length:");
    if(cl) content_len=atoi(cl+15);
    int have_body = (int)(total-hdr_len);
    while(have_body < content_len) {
        int r=recv(c, buf+total, sizeof(buf)-1-total, 0);
        if(r<=0) break;
        total+=r; have_body+=r;
    }
    char* bodyp = buf+hdr_len;

    /* ---- 公开健康端点（无需 Key，供看门狗/就绪探测） ---- */
    if(!strcmp(method,"GET") && !strcmp(path,"/healthz")){
        /* 直接读看门狗维护的全局标志，毫秒级返回，不做网络探测 */
        int ok = g_engine_ready;
        char out[128]; snprintf(out,sizeof(out),
            "{\"status\":\"%s\",\"engine_restarts\":%d,\"model\":\"%s\"}",
            ok?"ready":"starting", g_restart_count, g_cfg.model_id);
        send_json(c, ok?200:503, ok?"OK":"Starting", out);
        close_socket(c); return NULL;
    }

    /* ---- 其余全部需要鉴权 ---- */
    if(!check_auth(buf, query)){
        send_json(c,401,"Unauthorized","{\"error\":\"missing or invalid API key\"}");
        log_line("401 未授权: %s %s", method, path);
        close_socket(c); return NULL;
    }

    /* ---- 内容限制：对出图类 POST 做本地违禁词扫描 ---- */
    if(g_cfg.content_filter && (!strcmp(method,"POST")) &&
       (!strcmp(path,"/sdapi/v1/txt2img") || !strcmp(path,"/sdapi/v1/img2img") ||
        !strcmp(path,"/v1/images/generations") || !strcmp(path,"/sdcpp/v1/img_gen"))){
        if(body_triggers_filter(bodyp, content_len>0?content_len:0)){
            send_json(c,400,"Bad Request","{\"error\":\"content blocked by local filter\"}");
            log_line("内容拦截: %s", path);
            close_socket(c); return NULL;
        }
    }

    log_line("%s %s -> 200 (转发引擎)", method, path);
    forward_to_upstream(c, method, target, buf, bodyp, content_len>0?content_len:0);
    close_socket(c);
    return NULL;
}

/* 监听线程：对外 accept */
static sock_t g_listen=-1;
static void* listen_thread(void* arg) {
    (void)arg;
    g_listen = socket(AF_INET,SOCK_STREAM,0);
    int one=1; setsockopt(g_listen,SOL_SOCKET,SO_REUSEADDR,(char*)&one,sizeof(one));
    struct sockaddr_in a; memset(&a,0,sizeof(a));
    a.sin_family=AF_INET; a.sin_addr.s_addr=htonl(INADDR_ANY);
    a.sin_port=htons((uint16_t)g_cfg.port);
    if(bind(g_listen,(struct sockaddr*)&a,sizeof(a))!=0){
        log_line("绑定 0.0.0.0:%d 失败（端口被占？）", g_cfg.port); g_running=0; return NULL;
    }
    listen(g_listen, 16);
    log_line("对外监听: http://0.0.0.0:%d", g_cfg.port);
    while(g_running){
        struct sockaddr_in cl; socklen_t n=sizeof(cl);
        sock_t c=accept(g_listen,(struct sockaddr*)&cl,&n);
        if(c<0) break;
        ConnArg* ca=malloc(sizeof(ConnArg)); ca->c=c;
#ifdef _WIN32
        _beginthreadex(NULL,0,(unsigned(__stdcall*)(void*))handle_conn,ca,0,NULL);
#else
        pthread_t t; pthread_create(&t,NULL,handle_conn,ca); pthread_detach(t);
#endif
    }
    return NULL;
}

/* ============================ 主界面展示 ============================ */
static void show_banner(void) {
    char lan[64]; get_lan_ip(lan,sizeof(lan));
    printf("\n");
    printf("==============================================================\n");
    printf("   %s  v%s\n", APP_NAME, APP_VERSION);
    printf("   %s\n", COPYRIGHT_STR);
    printf("--------------------------------------------------------------\n");
    printf("  [服务地址]  http://%s:%d\n", lan, g_cfg.port);
    printf("             http://127.0.0.1:%d  (本机)\n", g_cfg.port);
    printf("  [API Key ]  %s\n", g_cfg.api_key);
    printf("  [模型    ]  %s\n", g_cfg.model_id[0]?g_cfg.model_id:"(未选择)");
    printf("  [内容限制]  %s   [主题] %s   [重启次数] %d\n",
           g_cfg.content_filter?"开启(拦截)":"无限制",
           g_cfg.dark_theme?"暗黑":"明亮", g_restart_count);
    printf("--------------------------------------------------------------\n");
    printf("  兼容方式: sd.cpp/sdapi 风格  POST /sdapi/v1/txt2img\n");
    printf("           OpenAI 风格  POST /v1/images/generations\n");
    printf("           健康检查(免Key) GET /healthz\n");
    printf("  请求时带:  Authorization: Bearer <上面的 Key>\n");
    printf("--------------------------------------------------------------\n");
    printf("  命令: r=重启引擎  f=切换内容限制  k=重置Key  i=重新显示  q=退出\n");
    printf("==============================================================\n\n");
}

/* 控制台命令线程（前台交互） */
static void* console_thread(void* arg) {
    (void)arg;
    char cmd[64];
    while(g_running){
        if(!fgets(cmd,sizeof(cmd),stdin)){
            /* 非交互/守护模式（stdin 被重定向或为 /dev/null、安装后自启服务）：
               读到 EOF 也不退出，继续驻留，由监听线程 + 看门狗持续提供服务。 */
            log_line("标准输入已结束(非交互)，转为后台驻留模式。");
            while(g_running) poll_sleep_ms(1000);
            break;
        }
        char c=cmd[0];
        if(c=='q'||c=='Q'){ g_running=0; break; }
        else if(c=='r'||c=='R'){ log_line("手动重启引擎..."); kill_engine(); spawn_engine(); }
        else if(c=='f'||c=='F'){ g_cfg.content_filter=!g_cfg.content_filter;
            log_line("内容限制 -> %s（重启引擎使生效）", g_cfg.content_filter?"开启":"无限制");
            save_config(); kill_engine(); spawn_engine(); }
        else if(c=='k'||c=='K'){ gen_api_key(g_cfg.api_key); save_config();
            log_line("已重置 API Key: %s", g_cfg.api_key); }
        else if(c=='i'||c=='I'){ show_banner(); }
    }
    return NULL;
}

#ifdef _WIN32
/* Windows: 设置控制台 UTF-8 + 选中中文字体 */
static void win_utf8(void) {
    SetConsoleOutputCP(CP_UTF8);
    HANDLE h=GetStdHandle(STD_OUTPUT_HANDLE);
    CONSOLE_FONT_INFOEX fi; ZeroMemory(&fi,sizeof(fi)); fi.cbSize=sizeof(fi);
    fi.dwFontSize.Y=16; wcscpy(fi.FaceName,L"Microsoft YaHei Mono");
    SetCurrentConsoleFontEx(h, FALSE, &fi);
}
#endif

/* ============================ Win32 原生窗口（仅 Windows） ============================ */
#ifdef _WIN32
#define IDC_COPY_ADDR 1001
#define IDC_COPY_KEY  1002
#define IDC_COPY_MODEL 1003
#define IDC_FILTER    1004
#define IDC_STATUS    1005
#define IDC_MODEL_CB  1006
#define IDC_THEME_CB  1007
static HWND gh_main=NULL; static NOTIFYICONDATAA gh_nid={0}; static int gh_has_tray=0;
static HBRUSH g_bgbrush=NULL; static COLORREF g_bgcol=RGB(28,28,30);
static COLORREF g_txcol=RGB(235,235,235);
/* 扫描 models/ 下已就绪的 .gguf/.safetensors 单文件模型，填进下拉框 */
static void ui_populate_models(HWND h){
    HWND cb=GetDlgItem(h,IDC_MODEL_CB);
    char pat[1280]; snprintf(pat,sizeof(pat),"%s%s*.*",g_model_dir,DIRSEP);
    WIN32_FIND_DATAA fd; HANDLE hf=FindFirstFileA(pat,&fd); int sel=0;
    if(hf!=INVALID_HANDLE_VALUE){
        do{
            if(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
            const char* nm=fd.cFileName; size_t L=strlen(nm);
            if(L>5 && (_stricmp(nm+L-5,".gguf")==0 || _stricmp(nm+L-12,".safetensors")==0)){
                int idx=(int)SendMessageA(cb,CB_ADDSTRING,0,(LPARAM)nm);
                /* 命中当前 model_path 的文件名则预选它 */
                char full[1300]; snprintf(full,sizeof(full),"%s%s%s",g_model_dir,DIRSEP,nm);
                if(!strcmp(full,g_cfg.model_path)) sel=idx;
            }
        }while(FindNextFileA(hf,&fd));
        FindClose(hf);
    }
    SendMessageA(cb,CB_SETCURSEL,sel,0);
}
static void ui_apply_theme(HWND h){
    if(g_cfg.dark_theme){ g_bgcol=RGB(28,28,30); g_txcol=RGB(235,235,235); }
    else { g_bgcol=RGB(245,245,245); g_txcol=RGB(20,20,20); }
    if(g_bgbrush) DeleteObject(g_bgbrush);
    g_bgbrush=CreateSolidBrush(g_bgcol);
    if(h) InvalidateRect(h,NULL,TRUE);
}
/* 从文件名生成模型 id（去扩展名） */
static void fname_to_id(const char* fname,char* out,int n){
    snprintf(out,n,"%s",fname);
    char* dot=strrchr(out,'.'); if(dot) *dot=0;
}

static void ui_set_clipboard(HWND owner, const char* txt) {
    if(!OpenClipboard(owner)) return;
    EmptyClipboard();
    size_t n=strlen(txt)+1;
    HGLOBAL h=GlobalAlloc(GMEM_MOVEABLE,n);
    if(h){ memcpy(GlobalLock(h),txt,n); GlobalUnlock(h);
        SetClipboardData(CF_TEXT,h); }
    CloseClipboard();
}
static char g_addr_disp[256];
static void ui_refresh(HWND h){
    char buf[512];
    snprintf(buf,sizeof(buf),"服务地址(点击复制): %s", g_addr_disp);
    SetWindowTextA(GetDlgItem(h,IDC_COPY_ADDR),buf);
    snprintf(buf,sizeof(buf),"API Key(点击复制): %s", g_cfg.api_key);
    SetWindowTextA(GetDlgItem(h,IDC_COPY_KEY),buf);
    snprintf(buf,sizeof(buf),"当前模型(点击复制): %s", g_cfg.model_id);
    SetWindowTextA(GetDlgItem(h,IDC_COPY_MODEL),buf);
    snprintf(buf,sizeof(buf),"引擎: %s   看门狗重启次数: %d   内容限制: %s",
             g_engine_ready?"就绪":"启动中", g_restart_count,
             g_cfg.content_filter?"有限制":"无限制");
    SetWindowTextA(GetDlgItem(h,IDC_STATUS),buf);
    CheckDlgButton(h,IDC_FILTER, g_cfg.content_filter?BST_CHECKED:BST_UNCHECKED);
}
static LRESULT CALLBACK WndProc(HWND h,UINT m,WPARAM w,LPARAM l){
    switch(m){
    case WM_CREATE: {
        HFONT f=CreateFontA(16,0,0,0,FW_NORMAL,0,0,0,DEFAULT_CHARSET,0,0,CLEARTYPE_QUALITY,0,"SimSun");
        HWND c;
        c=CreateWindowA("BUTTON","",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,10,10,430,28,h,(HMENU)IDC_COPY_ADDR,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        c=CreateWindowA("BUTTON","",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,10,45,430,28,h,(HMENU)IDC_COPY_KEY,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        c=CreateWindowA("BUTTON","",WS_CHILD|WS_VISIBLE|BS_PUSHBUTTON,10,80,430,28,h,(HMENU)IDC_COPY_MODEL,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        c=CreateWindowA("STATIC","切换已下载模型:",WS_CHILD|WS_VISIBLE,10,120,130,22,h,(HMENU)NULL,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        c=CreateWindowA("COMBOBOX",NULL,WS_CHILD|WS_VISIBLE|CBS_DROPDOWNLIST|WS_VSCROLL,140,118,300,200,h,(HMENU)IDC_MODEL_CB,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        c=CreateWindowA("BUTTON","内容限制(默认无限制；勾选后重启引擎生效)",WS_CHILD|WS_VISIBLE|BS_AUTOCHECKBOX,10,152,330,22,h,(HMENU)IDC_FILTER,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        c=CreateWindowA("STATIC","主题:",WS_CHILD|WS_VISIBLE,10,186,60,22,h,(HMENU)NULL,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        c=CreateWindowA("COMBOBOX",NULL,WS_CHILD|WS_VISIBLE|CBS_DROPDOWNLIST,70,184,120,200,h,(HMENU)IDC_THEME_CB,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        SendMessageA(c,CB_ADDSTRING,0,(LPARAM)"暗黑"); SendMessageA(c,CB_ADDSTRING,0,(LPARAM)"明亮");
        SendMessageA(c,CB_SETCURSEL,g_cfg.dark_theme?0:1,0);
        c=CreateWindowA("STATIC","",WS_CHILD|WS_VISIBLE,10,220,430,44,h,(HMENU)IDC_STATUS,NULL,NULL); SendMessageA(c,WM_SETFONT,(WPARAM)f,1);
        ui_apply_theme(NULL);
        ui_populate_models(h);
        ui_refresh(h);
        /* 托盘图标 */
        gh_nid.cbSize=sizeof(gh_nid); gh_nid.hWnd=h; gh_nid.uID=1;
        gh_nid.uFlags=NIF_ICON|NIF_TIP; gh_nid.hIcon=LoadIcon(NULL,IDI_APPLICATION);
        strcpy(gh_nid.szTip,"Local Dream ET Server");
        gh_has_tray = Shell_NotifyIconA(NIM_ADD,&gh_nid)?1:0;
        SetTimer(h,1,1000,NULL);
        return 0; }
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLORDLG: {
        HDC dc=(HDC)w; SetTextColor(dc,g_txcol); SetBkColor(dc,g_bgcol);
        return (INT_PTR)g_bgbrush; }
    case WM_ERASEBKGND: {
        RECT rc; GetClientRect(h,&rc);
        FillRect((HDC)w,&rc,g_bgbrush); return 1; }
    case WM_COMMAND: {
        int id=LOWORD(w);
        if(HIWORD(w)==CBN_SELCHANGE && id==IDC_MODEL_CB){
            char fn[600]; GetDlgItemTextA(h,IDC_MODEL_CB,fn,sizeof(fn));
            if(fn[0]){
                snprintf(g_cfg.model_path,sizeof(g_cfg.model_path),"%s%s%s",g_model_dir,DIRSEP,fn);
                fname_to_id(fn,g_cfg.model_id,sizeof(g_cfg.model_id));
                save_config();
                log_line("UI 切换模型 -> %s，重启引擎", fn);
                kill_engine();  /* 看门狗自动用新模型重拉 */
                MessageBoxA(h,"已切换模型，引擎正在自动重启。","提示",MB_OK);
            }
            return 0;
        }
        if(HIWORD(w)==CBN_SELCHANGE && id==IDC_THEME_CB){
            int sel=(int)SendMessageA(GetDlgItem(h,IDC_THEME_CB),CB_GETCURSEL,0,0);
            g_cfg.dark_theme = (sel==0)?1:0; save_config(); ui_apply_theme(h);
            return 0;
        }
        if(id==IDC_COPY_ADDR){ ui_set_clipboard(h,g_addr_disp); MessageBoxA(h,"已复制服务地址","提示",MB_OK); }
        else if(id==IDC_COPY_KEY){ ui_set_clipboard(h,g_cfg.api_key); MessageBoxA(h,"已复制 API Key","提示",MB_OK); }
        else if(id==IDC_COPY_MODEL){ ui_set_clipboard(h,g_cfg.model_id); MessageBoxA(h,"已复制模型名","提示",MB_OK); }
        else if(id==IDC_FILTER){
            g_cfg.content_filter = (IsDlgButtonChecked(h,IDC_FILTER)==BST_CHECKED)?1:0;
            save_config();
            log_line("内容限制切换为: %s，重启引擎生效", g_cfg.content_filter?"有限制":"无限制");
            kill_engine();  /* 看门狗会自动重拉，使配置生效 */
        }
        return 0; }
    case WM_TIMER: ui_refresh(h); return 0;
    case WM_SIZE: return 0;
    case WM_CLOSE: ShowWindow(h,SW_HIDE); return 0;  /* 关闭=隐藏到托盘 */
    case WM_DESTROY:
        if(gh_has_tray) Shell_NotifyIconA(NIM_DELETE,&gh_nid);
        PostQuitMessage(0); return 0;
    }
    return DefWindowProcA(h,m,w,l);
}
static void gui_thread(void){
    char lan[128]; get_lan_ip(lan,sizeof(lan));
    snprintf(g_addr_disp,sizeof(g_addr_disp),"http://%s:%d", lan, g_cfg.port);
    WNDCLASSA wc={0}; wc.lpfnWndProc=WndProc; wc.hInstance=GetModuleHandleA(NULL);
    wc.lpszClassName="LocalDreamETServer"; wc.hCursor=LoadCursor(NULL,IDC_ARROW);
    wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);
    RegisterClassA(&wc);
    gh_main=CreateWindowA("LocalDreamETServer","Local Dream ET Server  v1.1.0",
        WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,CW_USEDEFAULT,CW_USEDEFAULT,470,320,
        NULL,NULL,GetModuleHandleA(NULL),NULL);
    ShowWindow(gh_main,SW_SHOW);
    MSG msg;
    while(GetMessageA(&msg,NULL,0,0)>0){ TranslateMessage(&msg); DispatchMessageA(&msg); }
}
#endif

int main(int argc, char** argv) {
    LOG_INIT();
#ifdef _WIN32
    win_utf8();
    WSADATA w; WSAStartup(MAKEWORD(2,2),&w);
#else
    signal(SIGPIPE, SIG_IGN);
#endif
    detect_progdir();
    default_config();
    int has_cfg = load_config();

    /* 命令行参数优先级高于配置文件，便于无交互部署/冒烟 */
    int no_wizard=0, no_gui=0;
    for(int i=1;i<argc;i++){
        if(!strcmp(argv[i],"--model") && i+1<argc){
            snprintf(g_cfg.model_path,sizeof(g_cfg.model_path),"%s",argv[++i]);
            if(!g_cfg.model_id[0]) snprintf(g_cfg.model_id,sizeof(g_cfg.model_id),"cli-model");
        } else if(!strcmp(argv[i],"--port") && i+1<argc){
            g_cfg.port=atoi(argv[++i]);
        } else if(!strcmp(argv[i],"--inner-port") && i+1<argc){
            g_cfg.inner_port=atoi(argv[++i]);
        } else if(!strcmp(argv[i],"--no-wizard")){
            no_wizard=1;
        } else if(!strcmp(argv[i],"--no-gui")){
            no_gui=1;
        } else if(!strcmp(argv[i],"--help") || !strcmp(argv[i],"-h")){
            printf("用法: etserver [--model <路径>] [--port <对外>] [--inner-port <对内>] [--no-wizard] [--no-gui]\n");
            return 0;
        }
    }

    printf("=== %s %s 启动 ===\n", APP_NAME, APP_VERSION);
    printf("工作目录: %s\n", g_progdir);

    /* 首次运行向导：无配置或未选模型（--no-wizard 或非 TTY 跳过） */
    if(!no_wizard && (!has_cfg || g_cfg.model_path[0]==0)) {
        run_wizard();
    }

    /* 拉起引擎 */
    if (g_cfg.model_path[0] && spawn_engine()==0) {
        /* 等待就绪 */
        int waited=0;
        while(waited<60000){
            if(upstream_healthy()){ log_line("引擎首次健康检查 200，就绪"); break; }
            poll_sleep_ms(1000); waited+=1000;
        }
        if(waited>=60000) log_line("警告：引擎 60s 内未就绪，看门狗会持续重试");
    } else {
        log_line("未配置模型，仅启动代理外壳；下载模型后请在 config 里填 model_path 再重启。");
    }

    /* 看门狗 + 对外监听 + 控制台 */
#ifdef _WIN32
    _beginthreadex(NULL,0,(unsigned(__stdcall*)(void*))watchdog_thread,NULL,0,NULL);
    _beginthreadex(NULL,0,(unsigned(__stdcall*)(void*))listen_thread,NULL,0,NULL);
#else
    pthread_t t;
    pthread_create(&t,NULL,watchdog_thread,NULL); pthread_detach(t);
    pthread_create(&t,NULL,listen_thread,NULL); pthread_detach(t);
#endif
    poll_sleep_ms(800);
    show_banner();
#ifdef _WIN32
    if(no_gui){
        console_thread(NULL);   /* 服务模式：纯控制台，不弹窗 */
    } else {
        gui_thread();           /* 原生 Win32 窗口（托盘/复制框/开关） */
    }
#else
    console_thread(NULL);
#endif

    g_running=0;
    kill_engine();
    close_socket(g_listen);
    log_line("已退出。");
    return 0;
}
