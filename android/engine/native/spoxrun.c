// spoxrun: starts a Linux program and watches it from outside (ptrace). Android blocks some
// Linux system calls for apps and stops the program when one is used. spoxrun catches each
// such stop, answers "not supported" so the program can use its fallback, and keeps a count.
// It only wakes up for those rare moments, so the program runs at full speed otherwise.
// With SPOXRUN_ETC=<folder> set, file lookups under /etc/ (DNS servers, certificates, ...) are
// sent to that folder instead, since Android has no Linux-style /etc for them.
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
#define OPENAT 56
#define DUP3 24
#define PRCTL 167
#define SECCOMP 277
#define ETC_FD 977

struct bpf { unsigned short code; unsigned char jt, jf; unsigned int k; };
#define LD(k) { 0x20, 0, 0, k }
#define JEQ(k, t, f) { 0x15, t, f, k }
#define RET(k) { 0x06, 0, 0, k }

// only these calls (all "dirfd, path, ...") stop for spoxrun; everything else runs untouched
static struct bpf filter[] = {
    LD(4), JEQ(0xC00000B7, 1, 0), RET(0x7fff0000),      // not arm64: allow
    LD(0),
    JEQ(56, 5, 0),   // openat
    JEQ(79, 4, 0),   // newfstatat
    JEQ(291, 3, 0),  // statx
    JEQ(48, 2, 0),   // faccessat
    JEQ(439, 1, 0),  // faccessat2
    RET(0x7fff0000), // allow
    RET(0x7ff00000), // trace
};

static int starts(const char *s, const char *p) { while (*p) if (*s++ != *p++) return 0; return 1; }

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

    const char *etc = 0;
    for (char **e = envp; *e; e++) if (starts(*e, "SPOXRUN_ETC=")) etc = *e + 12;
    if (etc) {
        long fd = sys(OPENAT, -100, (long)etc, 0x200000 /* O_PATH */ | 0x4000 /* O_DIRECTORY */, 0, 0);
        if (fd >= 0) { sys(DUP3, fd, ETC_FD, 0, 0, 0); if (fd != ETC_FD) sys(57 /* close */, fd, 0, 0, 0, 0); }
        else { out("[spoxrun] etc folder missing\n"); etc = 0; }
    }

    long pid = sys(CLONE, 17 /* SIGCHLD */, 0, 0, 0, 0);
    if (pid == 0) {
        sys(PTRACE, 0 /* TRACEME */, 0, 0, 0, 0);
        if (etc) {
            struct { unsigned short len; struct bpf *f; } prog = { sizeof filter / sizeof filter[0], filter };
            sys(PRCTL, 38 /* NO_NEW_PRIVS */, 1, 0, 0, 0);
            long r = sys(SECCOMP, 1 /* SET_MODE_FILTER */, 0, (long)&prog, 0, 0);
            if (r < 0) { out("[spoxrun] seccomp filter not allowed "); num(r); out("\n"); }
        }
        sys(EXECVE, (long)argv[1], (long)(argv + 1), (long)envp, 0, 0);
        out("[spoxrun] could not start the program\n");
        sys(EXIT, 127, 0, 0, 0, 0);
    }
    if (pid < 0) { out("[spoxrun] fork failed "); num(pid); out("\n"); sys(EXIT, 126, 0, 0, 0, 0); }

    int st = 0;
    if (sys(WAIT4, pid, (long)&st, 0x40000000 /* __WALL */, 0, 0) < 0) quit(125);
    if ((st & 0xff) != 0x7f) { out("[spoxrun] program ended at once\n"); quit((st >> 8) & 0xff); }
    // follow every thread and child; if spoxrun dies, so does the program
    long r = sys(PTRACE, 0x4200 /* SETOPTIONS */, pid, 0, 2 | 4 | 8 | 0x80 /* TRACESECCOMP */ | 0x100000, 0);
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
        if ((st >> 16) == 7) {                            // one of the filtered file calls
            u64 regs[34];
            struct { void *base; u64 len; } iov = { regs, sizeof regs };
            if (sys(PTRACE, 0x4204, t, 1, (long)&iov, 0) == 0) {
                u64 w = 0;
                if (sys(PTRACE, 2 /* PEEKDATA */, t, (long)regs[1], (long)&w, 0) == 0) {
                    const char *c = (const char *)&w;
                    if (c[0] == '/' && c[1] == 'e' && c[2] == 't' && c[3] == 'c' && c[4] == '/') {
                        // "/etc/x" -> "x" inside our folder: no memory in the program is touched
                        regs[0] = ETC_FD;
                        regs[1] += 5;
                        sys(PTRACE, 0x4205, t, 1, (long)&iov, 0);
                    }
                }
            }
            give = 0;
        }
        else if (st >> 16) give = 0;                      // clone/fork/exec notice
        else if (sig == 31) {                             // SIGSYS
            unsigned char info[128];
            sys(PTRACE, 0x4202 /* GETSIGINFO */, t, 0, (long)info, 0);
            if (*(int *)(info + 8) == 1 /* SYS_SECCOMP */) {
                int nr = *(int *)(info + 24);
                u64 rr[34];
                struct { void *base; u64 len; } io = { rr, sizeof rr };
                if (nr == 202 /* accept */ && sys(PTRACE, 0x4204, t, 1, (long)&io, 0) == 0) {
                    // Android allows accept4 but not plain accept: run it again as accept4(..., 0)
                    if (counts[nr]++ < 1) out("[spoxrun] accept -> accept4\n");
                    rr[8] = 242; rr[3] = 0; rr[32] -= 4;
                    sys(PTRACE, 0x4205, t, 1, (long)&io, 0);
                    sys(PTRACE, 7, t, 0, 0, 0);
                    continue;
                }
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
