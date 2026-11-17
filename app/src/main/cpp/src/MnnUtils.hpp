#ifndef MNNUTILS_HPP
#define MNNUTILS_HPP

#include <MNN/MNNDefine.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <MNN/Interpreter.hpp>
#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <system_error>
#include <thread>

// Returns "{model_dir}/cache", creating it if needed. Returns "" when
// model_dir is empty or directory creation fails; callers must treat that
// as "caching disabled for this run".
inline std::string ensureCacheDir(const std::string &model_dir) {
  if (model_dir.empty()) return "";
  std::filesystem::path p = std::filesystem::path(model_dir) / "cache";
  std::error_code ec;
  std::filesystem::create_directories(p, ec);
  if (ec) return "";
  return p.string();
}

// Load an MNN model via mmap + createFromBuffer instead of createFromFile.
// createFromFile reads the whole .mnn in 4 KB chunks and then merges them into
// one contiguous buffer, transiently holding ~2x the model size in anonymous
// (non-reclaimable) memory. Mapping the file read-only keeps that source as
// clean, file-backed pages the kernel can reclaim under pressure, so the peak
// anonymous footprint during load drops to the single owned buffer MNN copies
// into. createFromBuffer copies the bytes, so the mapping can be released right
// away. Falls back to createFromFile on any mmap-path failure.
inline MNN::Interpreter *createMnnInterpreterMmap(const char *path) {
  int fd = open(path, O_RDONLY);
  if (fd < 0) {
    return MNN::Interpreter::createFromFile(path);
  }
  struct stat st{};
  if (0 != fstat(fd, &st) || st.st_size <= 0) {
    close(fd);
    return MNN::Interpreter::createFromFile(path);
  }
  size_t size = static_cast<size_t>(st.st_size);
  void *mapped = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
  // The mapping holds its own file reference, so the fd can be closed now.
  close(fd);
  if (MAP_FAILED == mapped) {
    return MNN::Interpreter::createFromFile(path);
  }
  // MNN copies the whole buffer once, sequentially; hint readahead to match.
  madvise(mapped, size, MADV_SEQUENTIAL);
  MNN::Interpreter *interpreter =
      MNN::Interpreter::createFromBuffer(mapped, size);
  munmap(mapped, size);
  if (interpreter) {
    // createFromFile sets a default external weight path; createFromBuffer does
    // not. Mirror it so models that store weights in a companion ".weight" file
    // still resolve them at session creation. Harmless when no such file
    // exists.
    interpreter->setExternalFile((std::string(path) + ".weight").c_str());
  }
  return interpreter;
}

// Session creation options shared by every MNN model in the pipeline.
// CPU: 4 threads, low-memory, high-power. OpenCL: fast tuning + buffer mode
// with low precision, plus an on-disk tuning cache when cache_file is set.
struct MnnSessionOptions {
  bool use_opencl = false;
  std::string cache_file;  // OpenCL tuning cache path ("" = no cache file)
  int num_threads = 4;
};

// Creates a session with the standard pipeline configuration. The interpreter
// keeps no reference to the local configs after createSession returns.
//
// et.244：线程数与 OpenCL tuning 之前是硬编码（4 线程 / TUNING_FAST），
// 8 核天玑、骁龙旗舰跑不满大核，Mali 也只挑到保守的 kernel。现在统一
// 从环境变量读，由 App 侧按机型注入：
//   SD_NUM_THREADS   : CPU 会话线程数（默认按大核数，32 位仍按地址空间钳制）
//   MNN_GPU_TUNING   : none|fast|normal|wide（默认 wide，Mali 上 kernel 选得更优）
//   MNN_GPU_MEM      : buffer|image（默认 buffer，Mali 上 buffer 更稳）
inline int mnnCpuThreads(int fallback) {
  int n = fallback;
  if (const char* e = getenv("SD_NUM_THREADS")) {
    int v = atoi(e);
    if (v >= 1 && v <= 32) n = v;
  }
  int cores = std::max(1, (int)std::thread::hardware_concurrency());
  n = std::min(n, cores);
  if (sizeof(void*) < 8) {
    // 32-bit address space is ~2-3GB: extra thread stacks push peak RSS into
    // an OOM kill (exit 137). Hard cap for armeabi-v7a.
    n = std::min(n, 3);
  }
  return std::max(1, n);
}

inline unsigned int mnnGpuMode() {
  unsigned int tuning = MNN_GPU_TUNING_WIDE;
  if (const char* e = getenv("MNN_GPU_TUNING")) {
    std::string s(e);
    if (s == "none") tuning = MNN_GPU_TUNING_NONE;
    else if (s == "fast") tuning = MNN_GPU_TUNING_FAST;
    else if (s == "normal") tuning = MNN_GPU_TUNING_NORMAL;
    else if (s == "wide") tuning = MNN_GPU_TUNING_WIDE;
  }
  unsigned int mem = MNN_GPU_MEMORY_BUFFER;
  if (const char* e = getenv("MNN_GPU_MEM")) {
    std::string s(e);
    if (s == "image") mem = MNN_GPU_MEMORY_IMAGE;
    else if (s == "buffer") mem = MNN_GPU_MEMORY_BUFFER;
  }
  return tuning | mem;
}

// et.244: GPU 后端可选 Vulkan 或 OpenCL。MNN 自带官方开源 Vulkan 后端
// （着色器已在源码里预编译，不需要 NDA、不需要厂商 SDK），天玑 9000+ 的
// Mali-G710/G715/G925 支持 Vulkan 1.1/1.2，部分负载比 OpenCL 更快。
// 由 SD_MNN_GPU=vulkan|opencl 决定；会话创建失败会逐级降级
// Vulkan -> OpenCL -> CPU，绝不再把"后端启动失败"抛给用户。
inline MNNForwardType mnnGpuForward() {
  if (const char* e = getenv("SD_MNN_GPU")) {
    std::string s(e);
    if (s == "vulkan") return MNN_FORWARD_VULKAN;
    if (s == "opencl") return MNN_FORWARD_OPENCL;
  }
  return MNN_FORWARD_OPENCL;
}

inline MNN::Session *createMnnSessionGpu(MNN::Interpreter *interpreter,
                                         const MnnSessionOptions &opts,
                                         MNNForwardType type) {
  MNN::ScheduleConfig config;
  MNN::BackendConfig backendConfig;
  if (!opts.cache_file.empty()) interpreter->setCacheFile(opts.cache_file.c_str());
  config.type = type;
  config.mode = mnnGpuMode();
  config.numThread = mnnCpuThreads(opts.num_threads);
  backendConfig.precision = MNN::BackendConfig::Precision_Low;
  backendConfig.power = MNN::BackendConfig::Power_High;
  config.backendConfig = &backendConfig;
  return interpreter->createSession(config);
}

inline MNN::Session *createMnnSession(MNN::Interpreter *interpreter,
                                      const MnnSessionOptions &opts) {
  MNN::ScheduleConfig config;
  MNN::BackendConfig backendConfig;
  if (opts.use_opencl) {
    MNN::Session *sess = nullptr;
    const MNNForwardType want = mnnGpuForward();
    if (want == MNN_FORWARD_VULKAN) sess = createMnnSessionGpu(interpreter, opts, MNN_FORWARD_VULKAN);
    if (!sess) sess = createMnnSessionGpu(interpreter, opts, MNN_FORWARD_OPENCL);
    if (sess) return sess;
    // GPU 两个后端都不可用（驱动缺失/被禁用）：落到 CPU，出图慢但一定出图。
    MNNPrint("MNN: GPU backend unavailable, falling back to CPU\n");
  }
  {
    config.type = MNN_FORWARD_CPU;
    // et.30: on 32-bit ABIs the address space is ~2-3GB and extra threads
    // stack memory pushes peak RSS over the limit (SIGKILL 137). Cap CPU
    // threads to 3 on 32-bit, keep 4 on 64-bit.
    int threads = mnnCpuThreads(opts.num_threads);
    config.numThread = threads;
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
  }
  backendConfig.power = MNN::BackendConfig::Power_High;
  config.backendConfig = &backendConfig;
  return interpreter->createSession(config);
}

#endif  // MNNUTILS_HPP
