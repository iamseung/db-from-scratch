#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static double now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e6 + ts.tv_nsec / 1e3;
}

/* 131바이트(엔진의 PUT 프레임 크기) append 후 동기화를 n회 반복한 평균 µs */
static double run(const char *path, int full, int n) {
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY | O_APPEND, 0644);
    char buf[131];
    memset(buf, 'x', sizeof buf);
    double start = now_us();
    for (int i = 0; i < n; i++) {
        write(fd, buf, sizeof buf);
        if (full) fcntl(fd, F_FULLFSYNC); else fsync(fd);
    }
    double avg = (now_us() - start) / n;
    close(fd);
    unlink(path);
    return avg;
}

int main(int argc, char **argv) {
    printf("fsync       : %8.1f us/write\n", run(argv[1], 0, 2000));
    printf("F_FULLFSYNC : %8.1f us/write\n", run(argv[1], 1, 2000));
    return 0;
}
