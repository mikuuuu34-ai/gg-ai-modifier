// 测试靶子：在堆上放置若干已知类型的值，可通过 stdin 改值，用于验证 scanner_root 协议
// 编译: gcc -O0 victim.c -o victim
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(void) {
    int   *islot = malloc(sizeof(int)   * 64);
    float *fslot = malloc(sizeof(float) * 64);
    short *wslot = malloc(sizeof(short) * 64);
    signed char *bslot = malloc(64);
    long long   *qslot = malloc(sizeof(long long) * 64);

    memset(islot, 0, sizeof(int) * 64);
    memset(fslot, 0, sizeof(float) * 64);
    memset(wslot, 0, sizeof(short) * 64);
    memset(bslot, 0, 64);
    memset(qslot, 0, sizeof(long long) * 64);

    islot[0] = 12345678;
    fslot[0] = 3.5f;
    wslot[0] = (short)4242;
    bslot[0] = (signed char)77;
    qslot[0] = 1234567890123LL;

    printf("pid=%d int=%p float=%p word=%p byte=%p qword=%p\n",
           getpid(), (void*)islot, (void*)fslot, (void*)wslot, (void*)bslot, (void*)qslot);
    fflush(stdout);

    char line[256];
    while (fgets(line, sizeof(line), stdin)) {
        int v;
        if (line[0] == 'q') break;
        if (sscanf(line, "set %d", &v) == 1) {
            islot[0] = v;
            printf("ok %d\n", islot[0]);
        } else if (strncmp(line, "get", 3) == 0) {
            printf("val %d\n", islot[0]);
        } else {
            printf("?\n");
        }
        fflush(stdout);
    }
    return 0;
}
