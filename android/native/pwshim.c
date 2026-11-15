// pwshim: a tiny stand-in for PipeWire's client library (libpipewire-0.3.so.0), for SpoX Engine.
//
// Soloist plays sound through PipeWire, which Android doesn't have. This library has the same
// name and the functions a simple PipeWire player uses, but instead of talking to a PipeWire
// server it sends the sound over a local socket to the SpoX Engine app, which plays it with
// Android's own audio (AudioTrack). It writes "[pw] ..." lines so the report shows what Soloist
// asks for.
//
// Build: aarch64-linux-gnu-gcc -shared -fPIC -O2 -o libpipewire-0.3.so.0 pwshim.c -lpthread
#define _GNU_SOURCE
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>

#define EXPORT __attribute__((visibility("default")))

static void say(const char *fmt, ...) {
    char b[300];
    va_list ap; va_start(ap, fmt);
    int n = vsnprintf(b, sizeof b - 1, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int)sizeof b - 2) n = sizeof b - 2;
    b[n++] = '\n';
    write(2, b, n);
}
// each function is reported once
#define ONCE(name) do { static int seen; if (!seen) { seen = 1; say("[pw] %s", name); } } while (0)

// ---- the SPA structures Soloist reads and writes (PipeWire's stable layout) ----------------

struct spa_list { struct spa_list *next, *prev; };
struct spa_callbacks { const void *funcs; void *data; };
struct spa_hook { struct spa_list link; struct spa_callbacks cb; void (*removed)(struct spa_hook *); void *priv; };
struct spa_interface { const char *type; uint32_t version; struct spa_callbacks cb; };
struct spa_chunk { uint32_t offset; uint32_t size; int32_t stride; int32_t flags; };
struct spa_data { uint32_t type; uint32_t flags; int64_t fd; uint32_t mapoffset; uint32_t maxsize; void *data; struct spa_chunk *chunk; };
struct spa_buffer { uint32_t n_metas; uint32_t n_datas; void *metas; struct spa_data *datas; };
struct pw_buffer { struct spa_buffer *buffer; void *user_data; uint64_t size; uint64_t requested; uint64_t time; };
struct spa_pod { uint32_t size; uint32_t type; };

struct pw_stream_events {
    uint32_t version;
    void (*destroy)(void *data);
    void (*state_changed)(void *data, int old, int state, const char *error);
    void (*control_info)(void *data, uint32_t id, const void *control);
    void (*io_changed)(void *data, uint32_t id, void *area, uint32_t size);
    void (*param_changed)(void *data, uint32_t id, const struct spa_pod *param);
    void (*add_buffer)(void *data, struct pw_buffer *buffer);
    void (*remove_buffer)(void *data, struct pw_buffer *buffer);
    void (*process)(void *data);
    void (*drained)(void *data);
    void (*command)(void *data, const void *command);
    void (*trigger_done)(void *data);
};

enum { ST_ERROR = -1, ST_UNCONNECTED = 0, ST_CONNECTING = 1, ST_PAUSED = 2, ST_STREAMING = 3 };

// ---- loop / lock --------------------------------------------------------------------------

static pthread_mutex_t big;
static pthread_cond_t cond = PTHREAD_COND_INITIALIZER;
static pthread_once_t init_once = PTHREAD_ONCE_INIT;
static void make_lock(void) {
    pthread_mutexattr_t a;
    pthread_mutexattr_init(&a);
    pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&big, &a);
}
static void lk(void) { pthread_once(&init_once, make_lock); pthread_mutex_lock(&big); }
static void ul(void) { pthread_mutex_unlock(&big); }

// ---- objects: core, registry, bound objects ("proxies") -----------------------------------
// PipeWire calls methods on these through a table of function pointers (spa_interface). Ours
// answer like a tiny PipeWire server with one speaker: the SpoX phone speaker.

struct spa_dict_item { const char *key; const char *value; };
struct spa_dict { uint32_t flags; uint32_t n_items; const struct spa_dict_item *items; };

#define MAXL 8
struct obj {
    struct spa_interface iface;
    int kind;                       // 1 core, 2 registry, 3 node, 4 metadata, 5 other, 0 plain
    uint32_t id;
    const char *type;
    const void *ev[MAXL]; void *evd[MAXL]; int nev;          // interface listeners
    const void *pev[MAXL]; void *pevd[MAXL]; int npev;       // proxy listeners
    char user[512];
};

static const struct spa_dict_item sink_items[] = {
    { "media.class", "Audio/Sink" }, { "node.name", "spox.speaker" },
    { "node.description", "SpoX phone speaker" }, { "node.nick", "Phone" },
    { "audio.channels", "2" }, { "audio.rate", "44100" }, { "object.serial", "40" },
};
static const struct spa_dict sink_props = { 0, 7, sink_items };
static const struct spa_dict_item meta_items[] = { { "metadata.name", "default" }, { "object.serial", "41" } };
static const struct spa_dict meta_props = { 0, 2, meta_items };

static void add_l(const void **ev, void **evd, int *n, struct spa_hook *h, const void *events, void *data) {
    if (*n < MAXL) { ev[*n] = events; evd[*n] = data; (*n)++; }
    if (h) { memset(h, 0, sizeof *h); h->link.next = h->link.prev = &h->link; h->cb.funcs = events; h->cb.data = data; }
}

// tasks run a moment later on a helper thread (PipeWire answers asynchronously too)
typedef void (*task_fn)(void *a, long b);
static void *task_run(void *arg) {
    void **t = arg;
    usleep(3000);
    lk();
    ((task_fn)t[0])(t[1], (long)t[2]);
    pthread_cond_broadcast(&cond);
    ul();
    free(t);
    return NULL;
}
static void later(task_fn f, void *a, long b) {
    void **t = malloc(3 * sizeof(void *)); t[0] = (void *)f; t[1] = a; t[2] = (void *)b;
    pthread_t th; pthread_create(&th, NULL, task_run, t); pthread_detach(th);
}

// core events: {version, info, done, ping, error, remove_id, bound_id, add_mem, remove_mem, bound_props}
struct core_ev { uint32_t version; void (*info)(void *, const void *); void (*done)(void *, uint32_t, int); };
static void core_done(void *a, long seq) {
    struct obj *o = a;
    say("[pw] core done %ld", seq);
    for (int i = 0; i < o->nev; i++) { const struct core_ev *e = o->ev[i]; if (e && e->done) e->done(o->evd[i], 0, (int)seq); }
}
// registry events: {version, global(data, id, permissions, type, version, props), global_remove}
struct reg_ev { uint32_t version; void (*global)(void *, uint32_t, uint32_t, const char *, uint32_t, const struct spa_dict *); };
static void reg_globals(void *a, long n) {
    struct obj *o = a; (void)n;
    say("[pw] announcing the phone speaker");
    for (int i = 0; i < o->nev; i++) {
        const struct reg_ev *e = o->ev[i];
        if (!e || !e->global) continue;
        e->global(o->evd[i], 40, 0x1ff, "PipeWire:Interface:Node", 3, &sink_props);
        e->global(o->evd[i], 41, 0x1ff, "PipeWire:Interface:Metadata", 3, &meta_props);
    }
}
// node events: {version, info, param}; node info layout:
struct node_info { uint32_t id, max_in, max_out; uint64_t change_mask; uint32_t n_in, n_out; int state; const char *error; const struct spa_dict *props; void *params; uint32_t n_params; };
struct node_ev { uint32_t version; void (*info)(void *, const struct node_info *); };
// metadata events: {version, property(data, subject, key, type, value)}
struct meta_ev { uint32_t version; int (*property)(void *, uint32_t, const char *, const char *, const char *); };
// proxy events: {version, destroy, bound(data, global_id), removed, done, error, bound_props}
struct proxy_ev { uint32_t version; void (*destroy)(void *); void (*bound)(void *, uint32_t); };

static void obj_events(void *a, long n) {
    struct obj *o = a; (void)n;
    for (int i = 0; i < o->npev; i++) { const struct proxy_ev *e = o->pev[i]; if (e && e->bound) e->bound(o->pevd[i], o->id); }
    for (int i = 0; i < o->nev; i++) {
        if (o->kind == 3) {
            struct node_info info = { o->id, 0, 1, 0x1f, 0, 1, 3 /* running */, NULL, &sink_props, NULL, 0 };
            const struct node_ev *e = o->ev[i]; if (e && e->info) e->info(o->evd[i], &info);
        } else if (o->kind == 4) {
            const struct meta_ev *e = o->ev[i];
            if (e && e->property) {
                e->property(o->evd[i], 0, "default.audio.sink", "Spa:String:JSON", "{\"name\":\"spox.speaker\"}");
                e->property(o->evd[i], 0, "default.configured.audio.sink", "Spa:String:JSON", "{\"name\":\"spox.speaker\"}");
            }
        }
    }
}

static struct obj *new_obj(const char *type, int kind);

// method tables (first slot = version, then the methods in PipeWire's order)
static long m_noop(void) { return 0; }
static int m_add_listener(void *o_, struct spa_hook *h, const void *events, void *data) {
    struct obj *o = o_;
    add_l(o->ev, o->evd, &o->nev, h, events, data);
    if (o->kind == 2) later(reg_globals, o, 0);
    if (o->kind == 3 || o->kind == 4) later(obj_events, o, 0);
    return 0;
}
static int seqn = 1;
static int m_core_sync(void *o, uint32_t id, int seq) { (void)id; (void)seq; int s = ++seqn; later(core_done, o, s); return s; }
static void *m_core_get_registry(void *o, uint32_t version, size_t user) { (void)o; (void)version; (void)user; say("[pw] get_registry"); return new_obj("PipeWire:Interface:Registry", 2); }
static void *m_core_create_object(void *o, const char *factory, const char *type, uint32_t v, const void *props, size_t user) {
    (void)o; (void)v; (void)props; (void)user; say("[pw] create_object %s %s", factory ? factory : "?", type ? type : "?"); return new_obj(type, 5);
}
static void *m_reg_bind(void *o, uint32_t id, const char *type, uint32_t v, size_t user) {
    (void)o; (void)v; (void)user;
    say("[pw] bind %u %s", id, type ? type : "?");
    struct obj *b = new_obj(type, id == 40 ? 3 : id == 41 ? 4 : 5);
    b->id = id;
    return b;
}
static void *core_m[16], *reg_m[16], *obj_m[16];
static pthread_once_t tables_once = PTHREAD_ONCE_INIT;
static void make_tables(void) {
    for (int i = 0; i < 16; i++) core_m[i] = reg_m[i] = obj_m[i] = (void *)m_noop;
    core_m[0] = reg_m[0] = obj_m[0] = (void *)(uintptr_t)3;     // method table version
    core_m[1] = reg_m[1] = obj_m[1] = (void *)m_add_listener;
    core_m[3] = (void *)m_core_sync;
    core_m[6] = (void *)m_core_get_registry;
    core_m[7] = (void *)m_core_create_object;
    reg_m[2] = (void *)m_reg_bind;
}
static struct obj *new_obj(const char *type, int kind) {
    pthread_once(&tables_once, make_tables);
    struct obj *o = calloc(1, sizeof *o);
    o->iface.type = type;
    o->iface.version = 3;
    o->iface.cb.funcs = kind == 1 ? (void *)core_m : kind == 2 ? (void *)reg_m : (void *)obj_m;
    o->iface.cb.data = o;
    o->kind = kind;
    o->type = type;
    return o;
}

// proxy functions (every bound object, the registry and the core are proxies)
EXPORT int pw_proxy_add_object_listener(void *p, struct spa_hook *h, const void *funcs, void *data) {
    say("[pw] pw_proxy_add_object_listener");
    return m_add_listener(p, h, funcs, data);
}
EXPORT void pw_proxy_add_listener(void *p, struct spa_hook *h, const void *events, void *data) {
    struct obj *o = p; ONCE("pw_proxy_add_listener");
    add_l(o->pev, o->pevd, &o->npev, h, events, data);
    if (o->id) later(obj_events, o, 0);
}
EXPORT void pw_proxy_destroy(void *p) { (void)p; ONCE("pw_proxy_destroy"); }
EXPORT void *pw_proxy_get_user_data(void *p) { return ((struct obj *)p)->user; }
EXPORT uint32_t pw_proxy_get_bound_id(void *p) { return ((struct obj *)p)->id; }
EXPORT uint32_t pw_proxy_get_id(void *p) { return ((struct obj *)p)->id; }
EXPORT const char *pw_proxy_get_type(void *p, uint32_t *version) { if (version) *version = 3; return ((struct obj *)p)->type; }
EXPORT int pw_proxy_sync(void *p, int seq) { (void)seq; return m_core_sync(p, 0, 0); }
EXPORT void *pw_proxy_get_object_listeners(void *p) { (void)p; return NULL; }
EXPORT void pw_proxy_ref(void *p) { (void)p; }
EXPORT void pw_proxy_unref(void *p) { (void)p; }

struct pw_thread_loop { struct obj *loop; int running; };

EXPORT void pw_init(int *argc, char ***argv) { (void)argc; (void)argv; ONCE("pw_init"); lk(); ul(); }
EXPORT void pw_deinit(void) { ONCE("pw_deinit"); }
EXPORT const char *pw_get_library_version(void) { ONCE("pw_get_library_version"); return "1.4.2"; }
EXPORT const char *pw_get_headers_version(void) { return "1.4.2"; }
EXPORT int pw_check_library_version(int a, int b, int c) { (void)a; (void)b; (void)c; return 1; }
EXPORT const char *pw_get_application_name(void) { return "soloist"; }
EXPORT const char *pw_get_prgname(void) { return "soloist"; }
EXPORT const char *pw_get_user_name(void) { return "spox"; }
EXPORT const char *pw_get_host_name(void) { return "phone"; }
EXPORT const char *pw_get_client_name(void) { return "soloist"; }
EXPORT int pw_in_valgrind(void) { return 0; }

EXPORT struct pw_thread_loop *pw_thread_loop_new(const char *name, const void *props) {
    (void)name; (void)props; ONCE("pw_thread_loop_new");
    struct pw_thread_loop *l = calloc(1, sizeof *l);
    l->loop = new_obj("PipeWire:Interface:Loop", 0);
    return l;
}
EXPORT struct pw_thread_loop *pw_thread_loop_new_full(void *loop, const char *name, const void *props) { (void)loop; return pw_thread_loop_new(name, props); }
EXPORT void *pw_thread_loop_get_loop(struct pw_thread_loop *l) { ONCE("pw_thread_loop_get_loop"); return l->loop; }
EXPORT int pw_thread_loop_start(struct pw_thread_loop *l) { ONCE("pw_thread_loop_start"); l->running = 1; return 0; }
EXPORT void pw_thread_loop_stop(struct pw_thread_loop *l) { ONCE("pw_thread_loop_stop"); l->running = 0; }
EXPORT void pw_thread_loop_destroy(struct pw_thread_loop *l) { ONCE("pw_thread_loop_destroy"); (void)l; }
EXPORT void pw_thread_loop_lock(struct pw_thread_loop *l) { (void)l; lk(); }
EXPORT void pw_thread_loop_unlock(struct pw_thread_loop *l) { (void)l; ul(); }
EXPORT void pw_thread_loop_signal(struct pw_thread_loop *l, int wait_for_accept) { (void)l; (void)wait_for_accept; pthread_cond_broadcast(&cond); }
EXPORT void pw_thread_loop_wait(struct pw_thread_loop *l) {
    (void)l; ONCE("pw_thread_loop_wait");
    // a short wait: our events come from another thread, so wake up and let the caller re-check
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_nsec += 50 * 1000000; if (ts.tv_nsec >= 1000000000) { ts.tv_sec++; ts.tv_nsec -= 1000000000; }
    pthread_cond_timedwait(&cond, &big, &ts);
}
EXPORT int pw_thread_loop_timed_wait(struct pw_thread_loop *l, int secs) { (void)secs; pw_thread_loop_wait(l); return 0; }
EXPORT int pw_thread_loop_timed_wait_full(struct pw_thread_loop *l, const struct timespec *abstime) { (void)abstime; pw_thread_loop_wait(l); return 0; }
EXPORT int pw_thread_loop_get_time(struct pw_thread_loop *l, struct timespec *abstime, int64_t timeout) {
    (void)l; clock_gettime(CLOCK_REALTIME, abstime);
    int64_t ns = abstime->tv_nsec + timeout;
    abstime->tv_sec += ns / 1000000000; abstime->tv_nsec = ns % 1000000000;
    return 0;
}
EXPORT void pw_thread_loop_accept(struct pw_thread_loop *l) { (void)l; }
EXPORT int pw_thread_loop_in_thread(struct pw_thread_loop *l) { (void)l; return 0; }

// main loop (in case it's used instead of the thread loop)
struct pw_main_loop { struct obj *loop; volatile int quit; };
EXPORT struct pw_main_loop *pw_main_loop_new(const void *props) { (void)props; ONCE("pw_main_loop_new"); struct pw_main_loop *m = calloc(1, sizeof *m); m->loop = new_obj("PipeWire:Interface:Loop", 0); return m; }
EXPORT void *pw_main_loop_get_loop(struct pw_main_loop *m) { return m->loop; }
EXPORT int pw_main_loop_run(struct pw_main_loop *m) { ONCE("pw_main_loop_run"); while (!m->quit) usleep(50000); return 0; }
EXPORT int pw_main_loop_quit(struct pw_main_loop *m) { m->quit = 1; return 0; }
EXPORT void pw_main_loop_destroy(struct pw_main_loop *m) { (void)m; }

// context / core
EXPORT void *pw_context_new(void *loop, void *props, size_t user_data_size) { (void)loop; (void)props; (void)user_data_size; ONCE("pw_context_new"); return new_obj("PipeWire:Interface:Context", 0); }
EXPORT void pw_context_destroy(void *c) { (void)c; ONCE("pw_context_destroy"); }
EXPORT void *pw_context_connect(void *c, void *props, size_t user_data_size) { (void)c; (void)props; (void)user_data_size; ONCE("pw_context_connect"); return new_obj("PipeWire:Interface:Core", 1); }
EXPORT void *pw_context_connect_fd(void *c, int fd, void *props, size_t s) { (void)fd; return pw_context_connect(c, props, s); }
EXPORT int pw_core_disconnect(void *core) { (void)core; ONCE("pw_core_disconnect"); return 0; }
EXPORT void *pw_context_get_object(void *c, const char *type) { (void)c; (void)type; return NULL; }
EXPORT int pw_context_load_module(void *c, const char *n, const char *a, void *p) { (void)c; (void)n; (void)a; (void)p; return 0; }
EXPORT void *pw_core_get_context(void *core) { (void)core; return NULL; }

// properties: a simple key=value list (enough to read back what was set)
struct prop { char *k, *v; };
struct pw_properties { struct { uint32_t flags; uint32_t n_items; struct prop *items; } dict; int cap; };
static void props_set(struct pw_properties *p, const char *k, const char *v) {
    if (!p || !k) return;
    for (uint32_t i = 0; i < p->dict.n_items; i++) if (!strcmp(p->dict.items[i].k, k)) {
        free(p->dict.items[i].v); p->dict.items[i].v = v ? strdup(v) : strdup(""); return;
    }
    if ((int)p->dict.n_items >= p->cap) { p->cap = p->cap ? p->cap * 2 : 16; p->dict.items = realloc(p->dict.items, p->cap * sizeof(struct prop)); }
    p->dict.items[p->dict.n_items].k = strdup(k);
    p->dict.items[p->dict.n_items].v = v ? strdup(v) : strdup("");
    p->dict.n_items++;
}
EXPORT struct pw_properties *pw_properties_new(const char *key, ...) {
    ONCE("pw_properties_new");
    struct pw_properties *p = calloc(1, sizeof *p);
    va_list ap; va_start(ap, key);
    while (key) { const char *v = va_arg(ap, const char *); props_set(p, key, v); key = va_arg(ap, const char *); }
    va_end(ap);
    return p;
}
EXPORT struct pw_properties *pw_properties_new_dict(const void *dict) {
    struct pw_properties *p = calloc(1, sizeof *p);
    const struct { uint32_t flags; uint32_t n_items; struct prop *items; } *d = dict;
    if (d) for (uint32_t i = 0; i < d->n_items; i++) props_set(p, d->items[i].k, d->items[i].v);
    return p;
}
EXPORT struct pw_properties *pw_properties_new_string(const char *s) { (void)s; return calloc(1, sizeof(struct pw_properties)); }
EXPORT struct pw_properties *pw_properties_copy(const struct pw_properties *s) { return pw_properties_new_dict(s ? &s->dict : NULL); }
EXPORT int pw_properties_set(struct pw_properties *p, const char *k, const char *v) { props_set(p, k, v); return 1; }
EXPORT int pw_properties_setf(struct pw_properties *p, const char *k, const char *fmt, ...) {
    char b[256]; va_list ap; va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
    props_set(p, k, b); return 1;
}
EXPORT int pw_properties_setva(struct pw_properties *p, const char *k, const char *fmt, va_list ap) {
    char b[256]; vsnprintf(b, sizeof b, fmt, ap); props_set(p, k, b); return 1;
}
EXPORT const char *pw_properties_get(const struct pw_properties *p, const char *k) {
    if (!p) return NULL;
    for (uint32_t i = 0; i < p->dict.n_items; i++) if (!strcmp(p->dict.items[i].k, k)) return p->dict.items[i].v;
    return NULL;
}
EXPORT int pw_properties_update(struct pw_properties *p, const void *dict) {
    const struct { uint32_t flags; uint32_t n_items; struct prop *items; } *d = dict;
    if (d) for (uint32_t i = 0; i < d->n_items; i++) props_set(p, d->items[i].k, d->items[i].v);
    return d ? (int)d->n_items : 0;
}
EXPORT int pw_properties_update_string(struct pw_properties *p, const char *s, size_t n) { (void)p; (void)s; (void)n; return 0; }
EXPORT void pw_properties_clear(struct pw_properties *p) { if (p) p->dict.n_items = 0; }
EXPORT void pw_properties_free(struct pw_properties *p) { (void)p; }

// ---- the stream: where the sound goes ---------------------------------------------------

#define NBUF 4
#define FRAMES 2048
struct pw_stream {
    char name[64];
    const struct pw_stream_events *ev;
    void *data;
    int state, active, connected, alive;
    uint32_t rate, channels, format;
    struct pw_buffer pb[NBUF];
    struct spa_buffer sb[NBUF];
    struct spa_data sd[NBUF];
    struct spa_chunk ch[NBUF];
    int queued[NBUF];   // given back by the program with sound in it
    int out;            // given to the program
    int sock;
    pthread_t th;
    uint64_t frames_out;
    void *param;
    uint32_t param_size;
    struct spa_hook *hook;
};

static void set_state(struct pw_stream *s, int st) {
    int old = s->state;
    if (old == st) return;
    s->state = st;
    say("[pw] stream state %d -> %d", old, st);
    if (s->ev && s->ev->state_changed) s->ev->state_changed(s->data, old, st, NULL);
}

// look for rate / channels / format in the format the program asked for
static uint32_t find_prop(const uint32_t *w, uint32_t n, uint32_t key, uint32_t def) {
    for (uint32_t i = 0; i + 4 < n; i++)
        if (w[i] == key && w[i + 2] == 4 && (w[i + 3] == 4 /* Int */ || w[i + 3] == 3 /* Id */)) return w[i + 4];
    // a "choice" (several allowed values): take its default
    for (uint32_t i = 0; i + 8 < n; i++)
        if (w[i] == key && w[i + 3] == 19 /* Choice */) return w[i + 8];
    return def;
}

static int connect_android(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un a; memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    const char *name = "spox-audio";
    memcpy(a.sun_path + 1, name, strlen(name));   // abstract name (starts with a 0 byte)
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);
    // a small send buffer: less sound waiting in the pipe = lyrics and buttons closer to what you hear
    int sb = 16384; setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sb, sizeof sb);
    if (connect(fd, (struct sockaddr *)&a, len) < 0) { say("[pw] can't reach SpoX Engine's audio (%d)", errno); close(fd); return -1; }
    return fd;
}

static int send_all(int fd, const void *b, size_t n) {
    const char *p = b;
    while (n) { ssize_t w = write(fd, p, n); if (w <= 0) { if (errno == EINTR) continue; return -1; } p += w; n -= w; }
    return 0;
}

static uint32_t bytes_per_frame(struct pw_stream *s) {
    uint32_t bps = s->format == 0x103 ? 2 : 4;  // S16_LE : F32_LE / S32
    return bps * (s->channels ? s->channels : 2);
}

static void *run(void *arg) {
    struct pw_stream *s = arg;
    int said = 0;
    while (s->alive) {
        if (!s->alive) break;
        if (s->sock < 0) {
            s->sock = connect_android();
            if (s->sock < 0) { usleep(500000); continue; }
            uint32_t hdr[4] = { 0x58504f53 /* "SPOX" */, s->rate, s->channels, s->format };
            if (send_all(s->sock, hdr, sizeof hdr)) { close(s->sock); s->sock = -1; continue; }
            say("[pw] sending sound to SpoX Engine: %u Hz, %u channels, format 0x%x", s->rate, s->channels, s->format);
        }
        if (!s->active || s->state != ST_STREAMING) { usleep(20000); continue; }
        // ask the program to fill a buffer
        lk();
        if (!s->alive) { ul(); break; }
        if (s->ev && s->ev->process) s->ev->process(s->data);
        int got = -1;
        for (int i = 0; i < NBUF; i++) if (s->queued[i]) { got = i; s->queued[i] = 0; break; }
        ul();
        if (got < 0) { usleep(5000); continue; }
        struct spa_chunk *c = &s->ch[got];
        uint32_t n = c->size;
        if (n > s->sd[got].maxsize) n = s->sd[got].maxsize;
        if (n && !said) { said = 1; say("[pw] first sound: %u bytes", n); }
        // blocking write: Android's player takes it at playback speed, which paces everything
        if (n && send_all(s->sock, (char *)s->sd[got].data + c->offset, n)) {
            say("[pw] audio connection closed");
            close(s->sock); s->sock = -1;
        }
        s->frames_out += n / bytes_per_frame(s);
    }
    if (s->sock >= 0) { close(s->sock); s->sock = -1; }
    return NULL;
}

EXPORT struct pw_stream *pw_stream_new(void *core, const char *name, struct pw_properties *props) {
    (void)core; (void)props; ONCE("pw_stream_new");
    struct pw_stream *s = calloc(1, sizeof *s);
    snprintf(s->name, sizeof s->name, "%s", name ? name : "stream");
    s->sock = -1;
    s->rate = 44100; s->channels = 2; s->format = 0x11b;
    return s;
}
EXPORT struct pw_stream *pw_stream_new_simple(void *loop, const char *name, struct pw_properties *props,
                                              const struct pw_stream_events *events, void *data) {
    (void)loop; ONCE("pw_stream_new_simple");
    struct pw_stream *s = pw_stream_new(NULL, name, props);
    s->ev = events; s->data = data;
    return s;
}
EXPORT void pw_stream_add_listener(struct pw_stream *s, struct spa_hook *h, const struct pw_stream_events *events, void *data) {
    ONCE("pw_stream_add_listener");
    s->ev = events; s->data = data;
    if (h) { memset(h, 0, sizeof *h); h->link.next = h->link.prev = &h->link; h->cb.funcs = events; h->cb.data = data; s->hook = h; }
}
EXPORT int pw_stream_get_state(struct pw_stream *s, const char **error) { if (error) *error = NULL; return s->state; }
EXPORT const char *pw_stream_get_name(struct pw_stream *s) { return s->name; }
EXPORT void *pw_stream_get_core(struct pw_stream *s) { (void)s; return NULL; }
EXPORT const void *pw_stream_get_properties(struct pw_stream *s) { (void)s; return NULL; }
EXPORT int pw_stream_update_properties(struct pw_stream *s, const void *d) { (void)s; (void)d; return 0; }
EXPORT uint32_t pw_stream_get_node_id(struct pw_stream *s) { (void)s; return 77; }
EXPORT const char *pw_stream_state_as_string(int st) {
    switch (st) { case -1: return "error"; case 0: return "unconnected"; case 1: return "connecting"; case 2: return "paused"; case 3: return "streaming"; }
    return "?";
}

EXPORT int pw_stream_connect(struct pw_stream *s, int direction, uint32_t target, int flags, const struct spa_pod **params, uint32_t n_params) {
    (void)direction; (void)target;
    say("[pw] pw_stream_connect flags 0x%x, %u params", flags, n_params);
    if (n_params && params && params[0]) {
        const struct spa_pod *p = params[0];
        uint32_t words = (8 + p->size) / 4;
        const uint32_t *w = (const uint32_t *)p;
        s->format = find_prop(w, words, 0x10001, s->format);
        s->rate = find_prop(w, words, 0x10003, s->rate);
        s->channels = find_prop(w, words, 0x10004, s->channels);
        // keep a copy, marked as the chosen "Format" (id 4) instead of "EnumFormat" (id 3)
        s->param_size = 8 + p->size;
        s->param = malloc(s->param_size);
        memcpy(s->param, p, s->param_size);
        if (s->param_size >= 16) ((uint32_t *)s->param)[3] = 4;
    }
    say("[pw] format 0x%x, %u Hz, %u channels", s->format, s->rate, s->channels);
    uint32_t bpf = bytes_per_frame(s);
    for (int i = 0; i < NBUF; i++) {
        s->ch[i].stride = bpf;
        s->sd[i].type = 1; // MemPtr
        s->sd[i].maxsize = FRAMES * bpf;
        s->sd[i].data = calloc(1, FRAMES * bpf);
        s->sd[i].chunk = &s->ch[i];
        s->sd[i].fd = -1;
        s->sb[i].n_datas = 1;
        s->sb[i].datas = &s->sd[i];
        s->pb[i].buffer = &s->sb[i];
        s->pb[i].size = FRAMES;
        s->pb[i].requested = 1024;
    }
    s->connected = 1;
    s->active = 1;
    s->alive = 1;
    set_state(s, ST_CONNECTING);
    if (s->ev && s->ev->param_changed && s->param) s->ev->param_changed(s->data, 4, s->param);
    if (s->ev && s->ev->add_buffer) for (int i = 0; i < NBUF; i++) s->ev->add_buffer(s->data, &s->pb[i]);
    set_state(s, ST_PAUSED);
    set_state(s, ST_STREAMING);
    pthread_create(&s->th, NULL, run, s);
    return 0;
}

EXPORT int pw_stream_disconnect(struct pw_stream *s) {
    ONCE("pw_stream_disconnect");
    if (!s->connected) return 0;
    lk();
    s->alive = 0;   // the sound thread checks this under the same lock, then ends by itself
    ul();
    pthread_detach(s->th);
    s->connected = 0;
    set_state(s, ST_UNCONNECTED);
    return 0;
}
EXPORT void pw_stream_destroy(struct pw_stream *s) {
    ONCE("pw_stream_destroy");
    if (s->connected) pw_stream_disconnect(s);
    if (s->ev && s->ev->destroy) s->ev->destroy(s->data);
}
EXPORT int pw_stream_set_active(struct pw_stream *s, int active) {
    say("[pw] set_active %d", active);
    s->active = active;
    return 0;
}
EXPORT struct pw_buffer *pw_stream_dequeue_buffer(struct pw_stream *s) {
    ONCE("pw_stream_dequeue_buffer");
    // hand out the next buffer the program doesn't hold yet
    for (int k = 0; k < NBUF; k++) {
        int i = (s->out + k) % NBUF;
        if (!s->queued[i]) { s->out = (i + 1) % NBUF; s->ch[i].size = 0; s->ch[i].offset = 0; return &s->pb[i]; }
    }
    errno = EPIPE;
    return NULL;
}
EXPORT int pw_stream_queue_buffer(struct pw_stream *s, struct pw_buffer *b) {
    ONCE("pw_stream_queue_buffer");
    for (int i = 0; i < NBUF; i++) if (&s->pb[i] == b) { s->queued[i] = 1; return 0; }
    return -22;
}
EXPORT int pw_stream_return_buffer(struct pw_stream *s, struct pw_buffer *b) { (void)s; (void)b; return 0; }
EXPORT int pw_stream_flush(struct pw_stream *s, int drain) {
    ONCE("pw_stream_flush");
    for (int i = 0; i < NBUF; i++) s->queued[i] = 0;
    if (drain && s->ev && s->ev->drained) s->ev->drained(s->data);
    return 0;
}
EXPORT int pw_stream_update_params(struct pw_stream *s, const struct spa_pod **p, uint32_t n) { (void)s; (void)p; (void)n; ONCE("pw_stream_update_params"); return 0; }
EXPORT int pw_stream_set_control(struct pw_stream *s, uint32_t id, uint32_t n, float *v, ...) { (void)s; (void)id; (void)n; (void)v; ONCE("pw_stream_set_control"); return 0; }
EXPORT int pw_stream_set_error(struct pw_stream *s, int res, const char *fmt, ...) { (void)res; (void)fmt; set_state(s, ST_ERROR); return 0; }
EXPORT int pw_stream_is_driving(struct pw_stream *s) { (void)s; return 1; }
EXPORT int pw_stream_trigger_process(struct pw_stream *s) { (void)s; return 0; }
EXPORT int64_t pw_stream_get_nsec(struct pw_stream *s) { (void)s; struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000000000LL + t.tv_nsec; }

struct pw_time { int64_t now; struct { uint32_t num, denom; } rate; uint64_t ticks; int64_t delay; uint64_t queued; uint64_t buffered; uint32_t queued_buffers; uint32_t avail_buffers; uint64_t size; };
EXPORT int pw_stream_get_time_n(struct pw_stream *s, struct pw_time *t, size_t size) {
    ONCE("pw_stream_get_time_n");
    memset(t, 0, size);
    if (size >= 16) { t->now = pw_stream_get_nsec(s); t->rate.num = 1; t->rate.denom = s->rate; }
    if (size >= 24) t->ticks = s->frames_out;
    if (size >= 32) t->delay = s->rate / 10;  // about 100 ms sits in Android's player
    return 0;
}
EXPORT int pw_stream_get_time(struct pw_stream *s, struct pw_time *t) { return pw_stream_get_time_n(s, t, 64); }

// logging helpers some programs call
EXPORT void pw_log_set_level(int level) { (void)level; }
EXPORT void pw_log_logv(int l, const char *f, int line, const char *fn, const char *fmt, va_list ap) { (void)l; (void)f; (void)line; (void)fn; (void)fmt; (void)ap; }
EXPORT void pw_log_log(int l, const char *f, int line, const char *fn, const char *fmt, ...) { (void)l; (void)f; (void)line; (void)fn; (void)fmt; }
EXPORT int pw_log_level = 0;
EXPORT void *PW_LOG_TOPIC_DEFAULT = 0;
#include "pwstubs.h"
