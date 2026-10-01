#ifndef MNNUTILS_HPP
#define MNNUTILS_HPP

#include <MNN/MNNDefine.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <MNN/Interpreter.hpp>
#include <filesystem>
#include <string>
#include <system_error>

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
inline MNN::Session *createMnnSession(MNN::Interpreter *interpreter,
                                      const MnnSessionOptions &opts) {
  MNN::ScheduleConfig config;
  MNN::BackendConfig backendConfig;
  if (opts.use_opencl) {
    if (!opts.cache_file.empty()) {
      interpreter->setCacheFile(opts.cache_file.c_str());
    }
    config.type = MNN_FORWARD_OPENCL;
    config.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
  } else {
    config.type = MNN_FORWARD_CPU;
    config.numThread = opts.num_threads;
    backendConfig.memory = MNN::BackendConfig::Memory_Low;
  }
  backendConfig.power = MNN::BackendConfig::Power_High;
  config.backendConfig = &backendConfig;
  return interpreter->createSession(config);
}

#endif  // MNNUTILS_HPP
