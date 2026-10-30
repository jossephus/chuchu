/* Every C header the JNI bridge uses, translated once by build.zig into the
 * `c` module. Zig 0.17 removed @cImport; a single shared translation also
 * keeps JNI and libssh2 types identical across bridge files. */
#ifndef CHUCHU_C_H
#define CHUCHU_C_H

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <libssh2.h>
#include <libssh2_sftp.h>

#include "chuchu_jni_internal.h"

#endif
