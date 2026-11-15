// spoxrun: starts a Linux program and watches it from outside (ptrace). Android blocks some
// Linux system calls for apps and stops the program when one is used. spoxrun catches each
// such stop, answers "not supported" so the program can use its fallback, and keeps a count.
// It only wakes up for those rare moments, so the program runs at full speed otherwise.
// No libc: raw system calls only, so it runs on Android and on Linux alike.
typedef unsigned long u64;

static long sys(long n, long a, long b, long c, long d, long e) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4) : "memory");
    return x0;
}

#define WRITE 64
#define EXIT 94
#define PTRACE 117
#define CLONE 220
#define EXECVE 221
#define WAIT4 260

static void out(const char *s) { int n = 0; while (s[n]) n++; sys(WRITE, 2, (long)s, n, 0, 0); }
static void num(long v) {
    char d[24]; int k = 0;
    if (v < 0) { out("-"); v = -v; }
    if (v == 0) d[k++] = '0';
    while (v > 0) { d[k++] = '0' + v % 10; v /= 10; }
    char b[24]; int i = 0; while (k) b[i++] = d[--k]; b[i] = 0; out(b);
}

static unsigned counts[600];

static void summary(void) {
    int any = 0;
    for (int i = 0; i < 600; i++) if (counts[i]) {
        if (!any) out("[spoxrun] blocked calls answered:");
        any = 1; out(" "); num(i); out("x"); num(counts[i]);
    }
    if (any) out("\n");
}

__attribute__((noreturn)) static void quit(long code) { summary(); sys(EXIT, code, 0, 0, 0, 0); for (;;) {} }

__attribute__((used)) void start_c(long *sp) {
    long argc = sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    if (argc < 2) { out("usage: spoxrun program [args...]\n"); sys(EXIT, 2, 0, 0, 0, 0); }

    long pid = sys(CLONE, 17 /* SIGCHLD */, 0, 0, 0, 0);
    if (pid == 0) {
        sys(PTRACE, 0 /* TRACEME */, 0, 0, 0, 0);
        sys(EXECVE, (long)argv[1], (long)(argv + 1), (long)envp, 0, 0);
        out("[spoxrun] could not start the program\n");
        sys(EXIT, 127, 0, 0, 0, 0);
    }
    if (pid < 0) { out("[spoxrun] fork failed "); num(pid); out("\n"); sys(EXIT, 126, 0, 0, 0, 0); }

    int st = 0;
    if (sys(WAIT4, pid, (long)&st, 0x40000000 /* __WALL */, 0, 0) < 0) quit(125);
    if ((st & 0xff) != 0x7f) { out("[spoxrun] program ended at once\n"); quit((st >> 8) & 0xff); }
    // follow every thread and child; if spoxrun dies, so does the program
    long r = sys(PTRACE, 0x4200 /* SETOPTIONS */, pid, 0, 2 | 4 | 8 | 0x100000, 0);
    if (r < 0) { out("[spoxrun] ptrace not allowed "); num(r); out("\n"); }
    sys(PTRACE, 7 /* CONT */, pid, 0, 0, 0);

    for (;;) {
        long t = sys(WAIT4, -1, (long)&st, 0x40000000, 0, 0);
        if (t < 0) quit(124);
        int low = st & 0x7f;
        if ((st & 0xff) != 0x7f) {                       // a thread or child ended
            if (t == pid) quit(low == 0 ? (st >> 8) & 0xff : 128 + low);
            continue;
        }
        int sig = (st >> 8) & 0xff;
        long give = sig;
        if (st >> 16) give = 0;                           // clone/fork/exec notice
        else if (sig == 31) {                             // SIGSYS
            unsigned char info[128];
            sys(PTRACE, 0x4202 /* GETSIGINFO */, t, 0, (long)info, 0);
            if (*(int *)(info + 8) == 1 /* SYS_SECCOMP */) {
                int nr = *(int *)(info + 24);
                if (nr >= 0 && nr < 600 && counts[nr]++ < 2) { out("[spoxrun] blocked syscall "); num(nr); out("\n"); }
                u64 regs[34];
                struct { void *base; u64 len; } iov = { regs, sizeof regs };
                if (sys(PTRACE, 0x4204 /* GETREGSET */, t, 1 /* NT_PRSTATUS */, (long)&iov, 0) == 0) {
                    regs[0] = (u64)-38;                   // -ENOSYS: "not supported"
                    sys(PTRACE, 0x4205 /* SETREGSET */, t, 1, (long)&iov, 0);
                }
                give = 0;
            }
        } else if (sig == 19 /* SIGSTOP */ || sig == 5 /* SIGTRAP */) give = 0;
        sys(PTRACE, 7, t, 0, give, 0);
    }
}

__asm__(".text\n.global _start\n_start:\n  mov x0, sp\n  bl start_c\n  brk #0\n");
