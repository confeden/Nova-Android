#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

typedef enum Tun2proxyVerbosity {
  Tun2proxyVerbosity_Off = 0,
  Tun2proxyVerbosity_Error,
  Tun2proxyVerbosity_Warn,
  Tun2proxyVerbosity_Info,
  Tun2proxyVerbosity_Debug,
  Tun2proxyVerbosity_Trace,
} Tun2proxyVerbosity;

typedef enum Tun2proxyDns {
  Tun2proxyDns_Virtual = 0,
  Tun2proxyDns_OverTcp,
  Tun2proxyDns_Direct,
} Tun2proxyDns;

typedef struct Tun2proxyTrafficStatus {
  uint64_t tx;
  uint64_t rx;
} Tun2proxyTrafficStatus;

#ifdef __cplusplus
extern "C" {
#endif

void tun2proxy_set_log_callback(void (*callback)(enum Tun2proxyVerbosity, const char*, void*),
                                void *ctx);

int tun2proxy_with_name_run(const char *proxy_url,
                            const char *tun,
                            const char *bypass,
                            enum Tun2proxyDns dns_strategy,
                            bool _root_privilege,
                            enum Tun2proxyVerbosity verbosity);

int tun2proxy_with_fd_run(const char *proxy_url,
                          int tun_fd,
                          bool close_fd_on_drop,
                          bool packet_information,
                          unsigned short tun_mtu,
                          enum Tun2proxyDns dns_strategy,
                          enum Tun2proxyVerbosity verbosity);

int tun2proxy_run_with_cli_args(const char *cli_args,
                                unsigned short tun_mtu,
                                bool packet_information);

int tun2proxy_stop(void);

void tun2proxy_set_traffic_status_callback(uint32_t send_interval_secs,
                                           void (*callback)(const struct Tun2proxyTrafficStatus*,
                                                            void*),
                                           void *ctx);

#ifdef __cplusplus
}
#endif
