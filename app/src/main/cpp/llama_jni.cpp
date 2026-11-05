#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cmath>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

namespace {

constexpr int   BATCH_SIZE            = 512;
constexpr int   CONTEXT_HEADROOM       = 8;
constexpr const char * ROLE_SYSTEM     = "system";
constexpr const char * ROLE_USER       = "user";
constexpr const char * ROLE_ASSISTANT  = "assistant";

llama_model *                 g_model = nullptr;
llama_context *               g_ctx   = nullptr;
common_batch                  g_batch;
common_chat_templates_ptr     g_tmpl;
common_sampler *              g_sampler = nullptr;
std::vector<common_chat_msg>  g_msgs;

llama_pos g_pos           = 0;
llama_pos g_stop_pos      = 0;
int       g_n_ctx         = 8192;
int       g_n_threads     = 4;
bool      g_turn_open     = false;
bool      g_backend_ready = false;

std::atomic<bool> g_cancel{false};
std::string       g_pending_chars;
std::ostringstream g_answer;

std::mutex g_lock;

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "localai", "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, "localai", "%s", text);
    }
}

std::string to_std(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string format_single(const std::string & role, const std::string & content, bool add_assistant) {
    std::vector<common_chat_msg> past = g_msgs;
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    return common_chat_format_single(g_tmpl.get(), past, msg, add_assistant, false);
}

std::vector<llama_token> tokenize(const std::string & text) {
    return common_tokenize(g_ctx, text, false, true);
}

bool is_valid_utf8(const std::string & value) {
    const unsigned char * bytes = reinterpret_cast<const unsigned char *>(value.c_str());
    while (*bytes != 0x00) {
        int n;
        if ((*bytes & 0x80) == 0x00) {
            n = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            n = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            n = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            n = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < n; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

void free_sampler() {
    if (g_sampler != nullptr) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
}

void reset_states(bool clear_msgs) {
    g_cancel.store(false);
    g_pending_chars.clear();
    g_answer.str("");
    g_answer.clear();
    g_turn_open = false;
    g_stop_pos = 0;
    if (clear_msgs) {
        g_msgs.clear();
        g_pos = 0;
    }
}

int decode_chunk(const std::vector<llama_token> & tokens, llama_pos start_pos, bool want_logits) {
    for (int offset = 0; offset < static_cast<int>(tokens.size()); offset += BATCH_SIZE) {
        const int count = std::min(static_cast<int>(tokens.size()) - offset, BATCH_SIZE);
        g_batch.clear();
        for (int i = 0; i < count; ++i) {
            const int index = offset + i;
            const bool is_last = want_logits && (index == static_cast<int>(tokens.size()) - 1);
            g_batch.add(tokens[index], start_pos + index, 0, is_last);
        }
        if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, "localai", "decode failed at %d", start_pos + offset);
            return 2;
        }
    }
    return 0;
}

void finish_turn() {
    if (!g_turn_open) return;
    const std::string answer = g_answer.str();
    if (!answer.empty()) {
        common_chat_msg msg;
        msg.role = ROLE_ASSISTANT;
        msg.content = answer;
        g_msgs.push_back(msg);
    }
    g_turn_open = false;
}

void release_model() {
    free_sampler();
    g_tmpl.reset();
    g_msgs.clear();
    g_batch = common_batch();
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_pos = 0;
    g_stop_pos = 0;
    g_n_ctx = 8192;
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeInit(JNIEnv *, jobject /*unused*/) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_backend_ready) return;
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    g_backend_ready = true;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeLoadModel(
        JNIEnv * env, jobject /*unused*/, jstring jpath, jint n_ctx, jint n_threads, jint n_gpu_layers) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_backend_ready) {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
        g_backend_ready = true;
    }

    const std::string path = to_std(env, jpath);

    release_model();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;

    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "localai", "model load failed: %s", path.c_str());
        return 1;
    }

    g_n_threads = std::max(1, static_cast<int>(n_threads));
    const int trained = llama_model_n_ctx_train(g_model);
    int ctx_size = static_cast<int>(n_ctx);
    if (trained > 0 && ctx_size > trained) {
        ctx_size = trained;
    }
    g_n_ctx = std::max(512, ctx_size);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = g_n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = std::min(256, BATCH_SIZE);
    ctx_params.n_threads = g_n_threads;
    ctx_params.n_threads_batch = g_n_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "localai", "context creation failed");
        release_model();
        return 2;
    }

    g_batch = common_batch(g_ctx);
    g_tmpl = common_chat_templates_init(g_model, "");
    g_pos = 0;
    g_msgs.clear();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeReset(JNIEnv * env, jobject /*unused*/, jstring jsystem) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx == nullptr || g_tmpl == nullptr) return 1;

    reset_states(true);
    free_sampler();
    llama_memory_clear(llama_get_memory(g_ctx), true);

    const std::string system_prompt = to_std(env, jsystem);
    if (system_prompt.empty()) return 0;

    const std::string formatted = format_single(ROLE_SYSTEM, system_prompt, false);
    const std::vector<llama_token> tokens = tokenize(formatted);
    if (static_cast<int>(tokens.size()) >= g_n_ctx - CONTEXT_HEADROOM) {
        __android_log_print(ANDROID_LOG_ERROR, "localai", "system prompt too long");
        return 2;
    }
    if (decode_chunk(tokens, 0, false) != 0) return 3;

    common_chat_msg msg;
    msg.role = ROLE_SYSTEM;
    msg.content = system_prompt;
    g_msgs.push_back(msg);
    g_pos = static_cast<llama_pos>(tokens.size());
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeAppend(
        JNIEnv * env, jobject /*unused*/, jstring jrole, jstring jcontent) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx == nullptr || g_tmpl == nullptr) return 1;

    const std::string role = to_std(env, jrole);
    const std::string content = to_std(env, jcontent);
    const std::string formatted = format_single(role, content, false);
    const std::vector<llama_token> tokens = tokenize(formatted);

    if (g_pos + static_cast<llama_pos>(tokens.size()) >= g_n_ctx - CONTEXT_HEADROOM) {
        return 2;
    }
    if (decode_chunk(tokens, g_pos, false) != 0) return 3;

    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    g_msgs.push_back(msg);
    g_pos += static_cast<llama_pos>(tokens.size());
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeSendUser(
        JNIEnv * env,
        jobject /*unused*/,
        jstring jtext,
        jint n_predict,
        jfloat temp,
        jfloat top_p,
        jfloat top_k,
        jfloat min_p,
        jfloat repeat_penalty) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx == nullptr || g_tmpl == nullptr) return 1;

    reset_states(false);
    free_sampler();

    const std::string text = to_std(env, jtext);
    const std::string formatted = format_single(ROLE_USER, text, true);
    const std::vector<llama_token> tokens = tokenize(formatted);

    const int predict = std::max(16, static_cast<int>(n_predict));
    if (g_pos + static_cast<llama_pos>(tokens.size()) + predict >= g_n_ctx - CONTEXT_HEADROOM) {
        return 2;
    }
    if (decode_chunk(tokens, g_pos, true) != 0) return 3;

    common_chat_msg msg;
    msg.role = ROLE_USER;
    msg.content = text;
    g_msgs.push_back(msg);

    g_pos += static_cast<llama_pos>(tokens.size());
    g_stop_pos = g_pos + predict;
    g_turn_open = true;
    g_answer.str("");
    g_answer.clear();
    g_pending_chars.clear();

    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_p = top_p;
    sparams.top_k = static_cast<int>(top_k);
    sparams.min_p = min_p;
    sparams.penalty_repeat = repeat_penalty;
    sparams.penalty_last_n = 64;
    g_sampler = common_sampler_init(g_model, sparams);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeNextToken(JNIEnv * env, jobject /*unused*/) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx == nullptr || g_sampler == nullptr || !g_turn_open) return nullptr;

    if (g_cancel.load() || g_pos >= g_stop_pos || g_pos >= g_n_ctx - CONTEXT_HEADROOM) {
        finish_turn();
        return nullptr;
    }

    const llama_token token = common_sampler_sample(g_sampler, g_ctx, -1, false);
    if (token < 0) {
        finish_turn();
        return nullptr;
    }
    common_sampler_accept(g_sampler, token, true);

    g_batch.clear();
    g_batch.add(token, g_pos, 0, true);
    if (llama_process(g_ctx, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        finish_turn();
        return nullptr;
    }
    g_pos++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), token)) {
        finish_turn();
        return nullptr;
    }

    const std::string piece = common_token_to_piece(g_ctx, token, false);
    g_pending_chars += piece;
    if (!is_valid_utf8(g_pending_chars)) {
        return env->NewStringUTF("");
    }
    const std::string flushed = g_pending_chars;
    g_pending_chars.clear();
    g_answer << flushed;
    return env->NewStringUTF(flushed.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeCancel(JNIEnv *, jobject /*unused*/) {
    g_cancel.store(true);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeContextUsed(JNIEnv *, jobject /*unused*/) {
    std::lock_guard<std::mutex> guard(g_lock);
    return static_cast<jint>(g_pos);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeContextSize(JNIEnv *, jobject /*unused*/) {
    std::lock_guard<std::mutex> guard(g_lock);
    return static_cast<jint>(g_n_ctx);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeModelInfo(JNIEnv * env, jobject /*unused*/) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_model == nullptr) return env->NewStringUTF("");

    char desc[256] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    const double size_gb = static_cast<double>(llama_model_size(g_model)) / (1024.0 * 1024.0 * 1024.0);
    const double params_b = static_cast<double>(llama_model_n_params(g_model)) / 1e9;

    std::ostringstream info;
    info << desc << " | " << params_b << "B | " << size_gb << "GB | ctx " << g_n_ctx << " | threads " << g_n_threads;
    return env->NewStringUTF(info.str().c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeUnload(JNIEnv *, jobject /*unused*/) {
    std::lock_guard<std::mutex> guard(g_lock);
    release_model();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeShutdown(JNIEnv *, jobject /*unused*/) {
    std::lock_guard<std::mutex> guard(g_lock);
    release_model();
    if (g_backend_ready) {
        llama_backend_free();
        g_backend_ready = false;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_buxingjie_localai_llm_NativeLlama_nativeSystemInfo(JNIEnv * env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}
