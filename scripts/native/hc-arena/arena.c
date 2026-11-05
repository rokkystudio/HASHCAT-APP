/*
 * Heap shim for the ARM Mali OpenCL compiler (libGLES_mali.so).
 *
 * Symptom this fixes
 * ------------------
 * Some hashcat OpenCL kernels (for example module 22000 WPA-EAPOL, whose
 * source inlines the unrolled SHA1/PBKDF2 transforms into several entry
 * points) make the Mali LLVM front end allocate tens of thousands of small
 * blocks.  Bionic/Scudo backs enough of those allocations with individual
 * mappings that the process passes vm.max_map_count (65530 on Android), and
 * the compiler then fails with
 *
 *     mmap failed: Out of memory
 *
 * followed by SIGABRT (exit code 134), even though several GB of RAM are
 * still free.  Measured on Pixel 9 Pro XL (Mali-G715, Android 16):
 * 65601 mappings, ~8 GB RSS, ~10 GB available.
 *
 * The shim reserves one large virtual range, commits it in 64 MB steps and
 * serves every allocation from bump pointers plus per-size-class free lists.
 * The mapping count stays flat (measured: ~460 instead of 65601) and the
 * compiler finishes.
 *
 * Only load this into the hashcat child process, through LD_PRELOAD, and only
 * for workloads that need it.
 */

#define _GNU_SOURCE

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <pthread.h>

#if defined (__LP64__) || defined (_LP64)
#define ARENA_RESERVE   ((size_t) 64 * 1024 * 1024 * 1024)
#else
#define ARENA_RESERVE   ((size_t) 3 * 1024 * 1024 * 1024)
#endif

#define CHUNK_SIZE      ((size_t) 64 * 1024 * 1024)
#define ALIGNMENT       ((size_t) 16)
#define HEADER_SIZE     ((size_t) 32)
#define NCLASS          40

static char *g_base = NULL;
static size_t g_reserve = 0;
static size_t g_commit = 0;

static char *g_bump = NULL;
static size_t g_left = 0;

static void *g_free[NCLASS];

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static int size_class (size_t size)
{
  int cls = 5;

  while ((((size_t) 1) << cls) < size) cls++;

  if (cls >= NCLASS) cls = NCLASS - 1;

  return cls;
}

static size_t class_size (int cls)
{
  return ((size_t) 1) << cls;
}

/* Must be called with g_lock held. */
static void *arena_carve (size_t size)
{
  size = (size + ALIGNMENT - 1) & ~(ALIGNMENT - 1);

  if (size > g_left)
  {
    if (g_base == NULL)
    {
      g_reserve = ARENA_RESERVE;

      char *p = (char *) mmap (NULL, g_reserve, PROT_NONE,
                               MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);

      if (p == MAP_FAILED) return NULL;

      g_base = p;
      g_commit = 0;
    }

    size_t grow = (size + CHUNK_SIZE - 1) & ~(CHUNK_SIZE - 1);

    if (g_commit + grow > g_reserve) return NULL;

    if (mprotect (g_base + g_commit, grow, PROT_READ | PROT_WRITE) != 0) return NULL;

    g_bump = g_base + g_commit;
    g_left = grow;
    g_commit += grow;
  }

  char *result = g_bump;

  g_bump += size;
  g_left -= size;

  return result;
}

/* Must be called with g_lock held. Returns a block with HEADER_SIZE bytes. */
static void *arena_alloc_locked (int cls)
{
  void *block = g_free[cls];

  if (block != NULL)
  {
    g_free[cls] = *(void **) block;

    return block;
  }

  return arena_carve (class_size (cls));
}

void *malloc (size_t size)
{
  if (size == 0) size = 1;

  int cls = size_class (size + HEADER_SIZE + ALIGNMENT);

  pthread_mutex_lock (&g_lock);

  void *block = arena_alloc_locked (cls);

  pthread_mutex_unlock (&g_lock);

  if (block == NULL) return NULL;

  ((size_t *) block)[0] = (size_t) cls;
  ((size_t *) block)[1] = size;

  return (char *) block + HEADER_SIZE;
}

void free (void *ptr)
{
  if (ptr == NULL) return;

  char *block = (char *) ptr - HEADER_SIZE;

  size_t cls = ((size_t *) block)[0];

  if (cls >= NCLASS) return;

  pthread_mutex_lock (&g_lock);

  *(void **) block = g_free[cls];
  g_free[cls] = block;

  pthread_mutex_unlock (&g_lock);
}

void *calloc (size_t nmemb, size_t size)
{
  size_t total = nmemb * size;

  if (nmemb != 0 && total / nmemb != size) return NULL;

  void *ptr = malloc (total);

  if (ptr != NULL && total != 0) memset (ptr, 0, total);

  return ptr;
}

void *realloc (void *ptr, size_t size)
{
  if (ptr == NULL) return malloc (size);
  if (size == 0) { free (ptr); return NULL; }

  char *block = (char *) ptr - HEADER_SIZE;

  size_t old = ((size_t *) block)[1];

  /* Shrinking inside the same size class keeps the pointer and the RSS. */
  if (size <= old || size_class (size + HEADER_SIZE + ALIGNMENT) == ((size_t *) block)[0])
  {
    ((size_t *) block)[1] = size;

    return ptr;
  }

  void *fresh = malloc (size);

  if (fresh == NULL) return NULL;

  memcpy (fresh, ptr, old);

  free (ptr);

  return fresh;
}

void *memalign (size_t alignment, size_t size)
{
  if (alignment <= ALIGNMENT) return malloc (size);

  if (size == 0) size = 1;

  int cls = size_class (size + HEADER_SIZE + alignment);

  pthread_mutex_lock (&g_lock);

  void *block = arena_alloc_locked (cls);

  pthread_mutex_unlock (&g_lock);

  if (block == NULL) return NULL;

  uintptr_t addr = (uintptr_t) block + HEADER_SIZE;

  uintptr_t aligned = (addr + (alignment - 1)) & ~(uintptr_t) (alignment - 1);

  char *header = (char *) (aligned - HEADER_SIZE);

  ((size_t *) header)[0] = (size_t) cls;
  ((size_t *) header)[1] = size;

  return (void *) aligned;
}

int posix_memalign (void **memptr, size_t alignment, size_t size)
{
  if (alignment < sizeof (void *) || (alignment & (alignment - 1)) != 0) return 22;

  void *ptr = memalign (alignment, size);

  if (ptr == NULL) return 12;

  *memptr = ptr;

  return 0;
}

void *aligned_alloc (size_t alignment, size_t size)
{
  return memalign (alignment, size);
}

void *valloc (size_t size)
{
  return memalign (4096, size);
}

size_t malloc_usable_size (void *ptr)
{
  if (ptr == NULL) return 0;

  char *block = (char *) ptr - HEADER_SIZE;

  return ((size_t *) block)[1];
}
