#ifndef KEKKAI_TUN_DEVICE_H
#define KEKKAI_TUN_DEVICE_H

/* Stable first-party userspace/NDIS device contract. No Wintun dependency. */
#define KEKKAI_TUN_DEVICE_NAME L"KekkaiTun"
#define KEKKAI_TUN_DOS_PATH L"\\\\.\\Global\\KekkaiTun"
#define KEKKAI_TUN_MAX_PACKET 65575u
#define KEKKAI_TUN_ABI_VERSION 1u

struct kekkai_tun_packet_v1 {
  unsigned int abi_version;
  unsigned int length;
  unsigned char bytes[KEKKAI_TUN_MAX_PACKET];
};

#endif
