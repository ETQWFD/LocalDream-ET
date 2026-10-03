#ifndef SAFE_TENSOR_READER_HPP
#define SAFE_TENSOR_READER_HPP

#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

#include "FloatConversion.hpp"
#include "json.hpp"

struct TensorInfo {
  std::string dtype;
  std::vector<int> shape;
  std::vector<long> data_offsets;
};

class SafeTensorReader {
 private:
  std::string filename_;
  std::map<std::string, TensorInfo> tensor_map_;
  long header_size_;

  void parse_header() {
    std::ifstream file(filename_, std::ios::binary);
    if (!file.is_open()) {
      throw std::runtime_error("Cannot open file: " + filename_);
    }

    uint64_t header_size_raw;
    file.read(reinterpret_cast<char *>(&header_size_raw), 8);
    if (file.gcount() != 8) {
      throw std::runtime_error("Cannot read header size");
    }

    // A real safetensors header (JSON map) for even a multi-GB checkpoint is
    // only a few MB. A huge value here means the file is not safetensors
    // (pickle .ckpt / HTML error page / truncated download) and allocating a
    // vector of that size would throw std::length_error and abort the process
    // on 32-bit devices. Validate it against a sane cap and the real file size
    // before allocating anything.
    const uint64_t kMaxHeaderBytes = 256ULL * 1024ULL * 1024ULL;
    if (header_size_raw == 0 || header_size_raw > kMaxHeaderBytes) {
      throw std::runtime_error(
          "Invalid safetensors header length (" +
          std::to_string(header_size_raw) +
          " bytes): file is not a valid .safetensors (corrupted, truncated, "
          "or wrong format such as .ckpt)");
    }

    uint64_t file_size = 0;
    {
      const std::streamoff cur = file.tellg();
      file.seekg(0, std::ios::end);
      const std::streamoff end = file.tellg();
      file.seekg(cur, std::ios::beg);
      if (end > 0) file_size = static_cast<uint64_t>(end);
    }
    if (file_size != 0 &&
        file_size < 8ULL + header_size_raw) {
      throw std::runtime_error(
          "Truncated safetensors: header declares " +
          std::to_string(header_size_raw) +
          " bytes but file is only " + std::to_string(file_size) +
          " bytes (download is incomplete or corrupted)");
    }

    header_size_ = static_cast<long>(header_size_raw);

    std::vector<char> header_buffer(header_size_);
    file.read(header_buffer.data(), header_size_);
    if (file.gcount() != static_cast<std::streamsize>(header_size_)) {
      throw std::runtime_error("Cannot read header");
    }

    std::string header_str(header_buffer.begin(), header_buffer.end());
    nlohmann::json header_json;
    try {
      header_json = nlohmann::json::parse(header_str);
    } catch (const nlohmann::json::exception &e) {
      throw std::runtime_error("JSON parse error: " + std::string(e.what()));
    }

    for (auto &[tensor_name, tensor_info] : header_json.items()) {
      if (tensor_name == "__metadata__") {
        continue;
      }

      TensorInfo info;
      info.dtype = tensor_info["dtype"];
      info.shape = tensor_info["shape"].get<std::vector<int>>();
      info.data_offsets = tensor_info["data_offsets"].get<std::vector<long>>();

      tensor_map_[tensor_name] = info;
    }

    file.close();
  }

  // Element count must be computed in 64-bit: multiplying large dims in an
  // int overflowed to a negative/huge value, which then made vector::resize()
  // throw std::length_error during conversion.
  static uint64_t calculate_tensor_size(const std::vector<int> &shape) {
    uint64_t size = 1;
    for (int dim : shape) {
      if (dim < 0) {
        throw std::runtime_error("Invalid tensor shape (negative dimension)");
      }
      const uint64_t d = static_cast<uint64_t>(dim);
      if (d != 0 && size > std::numeric_limits<uint64_t>::max() / d) {
        throw std::runtime_error(
            "Tensor shape overflow: element count exceeds 64-bit range");
      }
      size *= d;
    }
    return size;
  }

 public:
  std::vector<float> data;
  std::vector<uint16_t> fp16_data;

  explicit SafeTensorReader(const std::string &filename)
      : filename_(filename), header_size_(0) {
    parse_header();
  }

  bool read(const std::string &tensor_name, bool convert = true) {
    auto it = tensor_map_.find(tensor_name);
    if (it == tensor_map_.end()) {
      throw std::runtime_error("Tensor not found: " + tensor_name);
    }

    const TensorInfo &info = it->second;

    if (info.dtype != "F16" && info.dtype != "F32" && info.dtype != "F64" &&
        info.dtype != "BF16") {
      throw std::runtime_error("Unsupported tensor dtype: " + info.dtype);
    }

    const uint64_t tensor_size = calculate_tensor_size(info.shape);
    const int64_t data_start = info.data_offsets[0];
    const int64_t data_end = info.data_offsets[1];

    // These are per-reader member buffers reused for every tensor; free any
    // previous tensor's payload so peak memory is one tensor, not the sum of
    // all tensors converted so far (matters on 32-bit devices).
    std::vector<float>().swap(data);
    std::vector<uint16_t>().swap(fp16_data);

    // Guarded resize: on a 32-bit process size_t is 32 bits; never hand a
    // larger count to vector::resize (that would throw length_error/abort).
    auto fit_size = [&](uint64_t elems) -> size_t {
      if (elems > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        throw std::runtime_error(
            "Tensor too large for this process address space: " +
            tensor_name);
      }
      return static_cast<size_t>(elems);
    };

    std::ifstream file(filename_, std::ios::binary);
    if (!file.is_open()) {
      throw std::runtime_error("Cannot open file: " + filename_);
    }

    file.seekg(static_cast<std::streamoff>(8 + header_size_) + data_start);

    if (info.dtype == "F16") {
      const int64_t expected_bytes = static_cast<int64_t>(tensor_size) * 2;
      if (data_end - data_start != expected_bytes) {
        throw std::runtime_error("Data size mismatch for tensor: " +
                                 tensor_name);
      }

      fp16_data.resize(fit_size(static_cast<size_t>(tensor_size)));
      file.read(reinterpret_cast<char *>(fp16_data.data()),
                static_cast<std::streamsize>(expected_bytes));
      if (file.gcount() != static_cast<std::streamsize>(expected_bytes)) {
        throw std::runtime_error("Cannot read tensor data: " + tensor_name);
      }

      if (convert) {
        data.resize(fit_size(static_cast<size_t>(tensor_size)));
        for (uint64_t i = 0; i < tensor_size; ++i) {
          data[i] = fp16_to_fp32(fp16_data[i]);
        }
      }
    } else if (info.dtype == "F32") {
      const int64_t expected_bytes = static_cast<int64_t>(tensor_size) * 4;
      if (data_end - data_start != expected_bytes) {
        throw std::runtime_error("Data size mismatch for tensor: " +
                                 tensor_name);
      }

      data.resize(fit_size(static_cast<size_t>(tensor_size)));
      file.read(reinterpret_cast<char *>(data.data()),
                static_cast<std::streamsize>(expected_bytes));
      if (file.gcount() != static_cast<std::streamsize>(expected_bytes)) {
        throw std::runtime_error("Cannot read tensor data: " + tensor_name);
      }

      fp16_data.resize(fit_size(static_cast<size_t>(tensor_size)));
      for (uint64_t i = 0; i < tensor_size; ++i) {
        fp16_data[i] = fp32_to_fp16(data[i]);
      }
    } else if (info.dtype == "F64") {
      const int64_t expected_bytes = static_cast<int64_t>(tensor_size) * 8;
      if (data_end - data_start != expected_bytes) {
        throw std::runtime_error("Data size mismatch for tensor: " +
                                 tensor_name);
      }

      std::vector<double> fp64_data(
          fit_size(static_cast<size_t>(tensor_size)));
      file.read(reinterpret_cast<char *>(fp64_data.data()),
                static_cast<std::streamsize>(expected_bytes));
      if (file.gcount() != static_cast<std::streamsize>(expected_bytes)) {
        throw std::runtime_error("Cannot read tensor data: " + tensor_name);
      }

      data.resize(fit_size(static_cast<size_t>(tensor_size)));
      fp16_data.resize(fit_size(static_cast<size_t>(tensor_size)));
      for (uint64_t i = 0; i < tensor_size; ++i) {
        data[i] = static_cast<float>(fp64_data[i]);
        fp16_data[i] = fp32_to_fp16(data[i]);
      }
    } else if (info.dtype == "BF16") {
      const int64_t expected_bytes = static_cast<int64_t>(tensor_size) * 2;
      if (data_end - data_start != expected_bytes) {
        throw std::runtime_error("Data size mismatch for tensor: " +
                                 tensor_name);
      }

      std::vector<uint16_t> bf16_temp(
          fit_size(static_cast<size_t>(tensor_size)));
      file.read(reinterpret_cast<char *>(bf16_temp.data()),
                static_cast<std::streamsize>(expected_bytes));
      if (file.gcount() != static_cast<std::streamsize>(expected_bytes)) {
        throw std::runtime_error("Cannot read tensor data: " + tensor_name);
      }

      data.resize(fit_size(static_cast<size_t>(tensor_size)));
      fp16_data.resize(fit_size(static_cast<size_t>(tensor_size)));
      for (uint64_t i = 0; i < tensor_size; ++i) {
        data[i] = bf16_to_fp32(bf16_temp[i]);
        fp16_data[i] = fp32_to_fp16(data[i]);
      }
    }

    file.close();
    return true;
  }

  bool has_tensor(const std::string &tensor_name) const {
    return tensor_map_.find(tensor_name) != tensor_map_.end();
  }

  std::vector<int> get_tensor_shape(const std::string &tensor_name) const {
    auto it = tensor_map_.find(tensor_name);
    if (it != tensor_map_.end()) {
      return it->second.shape;
    }
    return {};
  }

  std::vector<std::string> get_tensor_names() const {
    std::vector<std::string> names;
    for (const auto &pair : tensor_map_) {
      names.push_back(pair.first);
    }
    return names;
  }

  int get_tensor_count() const { return tensor_map_.size(); }
};

#endif  // SAFE_TENSOR_READER_HPP