#ifndef KEKKAI_KOTOBA_PACKET_PLANE_BRIDGE_H
#define KEKKAI_KOTOBA_PACKET_PLANE_BRIDGE_H

#include <stdint.h>

int64_t kekkai_packet_decide_v1(int64_t family, int64_t packet_length,
                                int64_t mtu, int64_t route_action,
                                int64_t transport_ready);

#endif
