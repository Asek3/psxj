#include "rc_client.h"
#include "rc_consoles.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#define PSXJ_EXPORT __declspec(dllexport)
#else
#define PSXJ_EXPORT __attribute__((visibility("default")))
#endif

enum {
  PSXJ_RA_LOGIN_OK = 1,
  PSXJ_RA_LOGIN_FAILED = 2,
  PSXJ_RA_GAME_LOADED = 3,
  PSXJ_RA_GAME_FAILED = 4,
  PSXJ_RA_ACHIEVEMENT_UNLOCKED = 5,
  PSXJ_RA_SERVER_ERROR = 6,
  PSXJ_RA_DISCONNECTED = 7,
  PSXJ_RA_RECONNECTED = 8
};

typedef uint32_t (*psxj_ra_read_callback_t)(uint32_t address, uint8_t* buffer,
    uint32_t num_bytes);
typedef void (*psxj_ra_http_callback_t)(const char* url, const char* post_data,
    const char* content_type, uint64_t request_id);
typedef void (*psxj_ra_event_callback_t)(int type, const char* title,
    const char* description, const char* detail, int value);
typedef void (*psxj_ra_log_callback_t)(const char* message);
typedef void (*psxj_ra_achievement_callback_t)(uint32_t id, const char* title,
    const char* description, uint32_t points, uint8_t state, uint8_t unlocked,
    int64_t unlock_time, const char* badge_url, const char* badge_locked_url);

typedef struct psxj_ra_context_t {
  rc_client_t* client;
  psxj_ra_read_callback_t read_callback;
  psxj_ra_http_callback_t http_callback;
  psxj_ra_event_callback_t event_callback;
  psxj_ra_log_callback_t log_callback;
} psxj_ra_context_t;

typedef struct psxj_ra_pending_request_t {
  rc_client_server_callback_t callback;
  void* callback_data;
} psxj_ra_pending_request_t;

static const char* safe_string(const char* value) {
  return value ? value : "";
}

static psxj_ra_context_t* context_for(rc_client_t* client) {
  return (psxj_ra_context_t*)rc_client_get_userdata(client);
}

static uint32_t read_memory(uint32_t address, uint8_t* buffer,
    uint32_t num_bytes, rc_client_t* client) {
  psxj_ra_context_t* context = context_for(client);
  return context && context->read_callback
      ? context->read_callback(address, buffer, num_bytes) : 0;
}

static void server_call(const rc_api_request_t* request,
    rc_client_server_callback_t callback, void* callback_data,
    rc_client_t* client) {
  psxj_ra_context_t* context = context_for(client);
  psxj_ra_pending_request_t* pending;
  if (!context || !context->http_callback) {
    rc_api_server_response_t response = { "HTTP callback unavailable", 25,
        RC_API_SERVER_RESPONSE_CLIENT_ERROR };
    callback(&response, callback_data);
    return;
  }
  pending = (psxj_ra_pending_request_t*)malloc(sizeof(*pending));
  if (!pending) {
    rc_api_server_response_t response = { "Out of memory", 13,
        RC_API_SERVER_RESPONSE_CLIENT_ERROR };
    callback(&response, callback_data);
    return;
  }
  pending->callback = callback;
  pending->callback_data = callback_data;
  context->http_callback(safe_string(request->url), request->post_data,
      request->content_type, (uint64_t)(uintptr_t)pending);
}

static void log_message(const char* message, const rc_client_t* client) {
  psxj_ra_context_t* context = context_for((rc_client_t*)client);
  if (context && context->log_callback)
    context->log_callback(safe_string(message));
}

static void login_callback(int result, const char* error_message,
    rc_client_t* client, void* userdata) {
  psxj_ra_context_t* context = context_for(client);
  const rc_client_user_t* user;
  (void)userdata;
  if (!context || !context->event_callback)
    return;
  if (result != RC_OK) {
    context->event_callback(PSXJ_RA_LOGIN_FAILED, "", safe_string(error_message), "", result);
    return;
  }
  user = rc_client_get_user_info(client);
  context->event_callback(PSXJ_RA_LOGIN_OK, safe_string(user->display_name),
      safe_string(user->username), safe_string(user->token), (int)user->score);
}

static void game_callback(int result, const char* error_message,
    rc_client_t* client, void* userdata) {
  psxj_ra_context_t* context = context_for(client);
  const rc_client_game_t* game;
  rc_client_user_game_summary_t summary;
  (void)userdata;
  if (!context || !context->event_callback)
    return;
  if (result != RC_OK) {
    context->event_callback(PSXJ_RA_GAME_FAILED, "", safe_string(error_message), "", result);
    return;
  }
  game = rc_client_get_game_info(client);
  memset(&summary, 0, sizeof(summary));
  rc_client_get_user_game_summary(client, &summary);
  context->event_callback(PSXJ_RA_GAME_LOADED, safe_string(game->title),
      safe_string(game->badge_url), safe_string(game->hash),
      (int)summary.num_unlocked_achievements);
}

static void event_handler(const rc_client_event_t* event, rc_client_t* client) {
  psxj_ra_context_t* context = context_for(client);
  if (!context || !context->event_callback)
    return;
  if (event->type == RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED && event->achievement) {
    const rc_client_achievement_t* achievement = event->achievement;
    context->event_callback(PSXJ_RA_ACHIEVEMENT_UNLOCKED,
        safe_string(achievement->title), safe_string(achievement->description),
        safe_string(achievement->badge_url), (int)achievement->points);
  } else if (event->type == RC_CLIENT_EVENT_SERVER_ERROR && event->server_error) {
    context->event_callback(PSXJ_RA_SERVER_ERROR,
        safe_string(event->server_error->api),
        safe_string(event->server_error->error_message), "",
        event->server_error->result);
  } else if (event->type == RC_CLIENT_EVENT_DISCONNECTED) {
    context->event_callback(PSXJ_RA_DISCONNECTED, "", "", "", 0);
  } else if (event->type == RC_CLIENT_EVENT_RECONNECTED) {
    context->event_callback(PSXJ_RA_RECONNECTED, "", "", "", 0);
  }
}

PSXJ_EXPORT void* psxj_ra_create(psxj_ra_read_callback_t read_callback,
    psxj_ra_http_callback_t http_callback,
    psxj_ra_event_callback_t event_callback,
    psxj_ra_log_callback_t log_callback) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)calloc(1, sizeof(*context));
  if (!context)
    return NULL;
  context->read_callback = read_callback;
  context->http_callback = http_callback;
  context->event_callback = event_callback;
  context->log_callback = log_callback;
  context->client = rc_client_create(read_memory, server_call);
  if (!context->client) {
    free(context);
    return NULL;
  }
  rc_client_set_userdata(context->client, context);
  rc_client_set_event_handler(context->client, event_handler);
  rc_client_enable_logging(context->client, RC_CLIENT_LOG_LEVEL_INFO, log_message);
  rc_client_set_hardcore_enabled(context->client, 0);
  return context;
}

PSXJ_EXPORT void psxj_ra_destroy(void* handle) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (!context)
    return;
  rc_client_destroy(context->client);
  free(context);
}

PSXJ_EXPORT void psxj_ra_login_password(void* handle, const char* username,
    const char* password) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_begin_login_with_password(context->client, username, password,
        login_callback, NULL);
}

PSXJ_EXPORT void psxj_ra_login_token(void* handle, const char* username,
    const char* token) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_begin_login_with_token(context->client, username, token,
        login_callback, NULL);
}

PSXJ_EXPORT void psxj_ra_logout(void* handle) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_logout(context->client);
}

PSXJ_EXPORT void psxj_ra_load_game(void* handle, const char* path) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_begin_identify_and_load_game(context->client, RC_CONSOLE_PLAYSTATION,
        path, NULL, 0, game_callback, NULL);
}

PSXJ_EXPORT void psxj_ra_unload_game(void* handle) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_unload_game(context->client);
}

PSXJ_EXPORT void psxj_ra_do_frame(void* handle) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_do_frame(context->client);
}

PSXJ_EXPORT void psxj_ra_idle(void* handle) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_idle(context->client);
}

PSXJ_EXPORT void psxj_ra_reset(void* handle) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  if (context)
    rc_client_reset(context->client);
}

PSXJ_EXPORT int psxj_ra_enumerate_achievements(void* handle,
    psxj_ra_achievement_callback_t callback) {
  psxj_ra_context_t* context = (psxj_ra_context_t*)handle;
  rc_client_achievement_list_t* list;
  uint32_t bucket_index;
  int count = 0;
  if (!context || !callback)
    return 0;
  list = rc_client_create_achievement_list(context->client,
      RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE_AND_UNOFFICIAL,
      RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_LOCK_STATE);
  if (!list)
    return 0;
  for (bucket_index = 0; bucket_index < list->num_buckets; ++bucket_index) {
    const rc_client_achievement_bucket_t* bucket = &list->buckets[bucket_index];
    uint32_t achievement_index;
    for (achievement_index = 0;
        achievement_index < bucket->num_achievements; ++achievement_index) {
      const rc_client_achievement_t* achievement =
          bucket->achievements[achievement_index];
      callback(achievement->id, safe_string(achievement->title),
          safe_string(achievement->description), achievement->points,
          achievement->state, achievement->unlocked,
          (int64_t)achievement->unlock_time,
          safe_string(achievement->badge_url),
          safe_string(achievement->badge_locked_url));
      ++count;
    }
  }
  rc_client_destroy_achievement_list(list);
  return count;
}

PSXJ_EXPORT void psxj_ra_complete_http(void* handle, uint64_t request_id,
    int status_code, const uint8_t* body, uint64_t body_length) {
  psxj_ra_pending_request_t* pending =
      (psxj_ra_pending_request_t*)(uintptr_t)request_id;
  rc_api_server_response_t response;
  (void)handle;
  if (!pending)
    return;
  response.body = (const char*)body;
  response.body_length = (size_t)body_length;
  response.http_status_code = status_code;
  pending->callback(&response, pending->callback_data);
  free(pending);
}
