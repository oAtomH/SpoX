// sysguard: a tiny add-on loaded into Soloist (LD_PRELOAD). Android blocks some Linux
// system calls for apps and kills the program when one is used ("Bad system call").
// This catches that, writes which call it was, and answers "not supported" instead,
// so the program can carry on with its fallback. No libc: raw system calls only.
typedef unsigned long u64;

static long sys(long n, long a, long b, long c, long d) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory");
    return x0;
}

static void say(int nr) {
    char b[48] = "[sysguard] blocked syscall ";
    int i = 27;
    char d[8]; int k = 0;
    if (nr <= 0) d[k++] = '0';
    while (nr > 0 && k < 8) { d[k++] = '0' + nr % 10; nr /= 10; }
    while (k) b[i++] = d[--k];
    b[i++] = '\n';
    sys(64, 2, (long)b, i, 0); // write(stderr)
}

static void on_sigsys(int sig, void *info, void *uc) {
    (void)sig;
    int nr = *(int *)((char *)info + 24);       // siginfo.si_syscall
    say(nr);
    *(long *)((char *)uc + 184) = -38;          // x0 = -ENOSYS
}

struct ksa { void *h; u64 flags; void *restorer; u64 mask; };

__attribute__((constructor)) static void init(void) {
    struct ksa sa = { (void *)on_sigsys, 4 /* SA_SIGINFO */ | 0x40000000 /* SA_NODEFER */, 0, 0 };
    sys(134, 31 /* SIGSYS */, (long)&sa, 0, 8); // rt_sigaction
}
