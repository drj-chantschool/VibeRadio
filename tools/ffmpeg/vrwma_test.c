/*
 * Host test for the vrwma core: decodes a file of back-to-back media objects (what
 * `ffmpeg -i x.wma -map 0:a -c copy -f data out.bin` writes) to raw float32 PCM on stdout.
 *
 *   vrwma_test <tag> <channels> <rate> <bit_rate> <block_align> <bits> <extradata hex|-> <packets.bin> > pcm.f32
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct VrWma VrWma;
VrWma *vrwma_open(int, int, int, int, int, int, const uint8_t *, int);
int vrwma_decode(VrWma *, const uint8_t *, int, float *, int);
void vrwma_close(VrWma *);

int main(int argc, char **argv)
{
    if (argc < 9) return 2;
    int tag = atoi(argv[1]), ch = atoi(argv[2]), rate = atoi(argv[3]), br = atoi(argv[4]);
    int align = atoi(argv[5]), bits = atoi(argv[6]);
    uint8_t extra[64];
    int n = 0;
    if (strcmp(argv[7], "-")) {
        for (const char *p = argv[7]; p[0] && p[1] && n < 64; p += 2) {
            unsigned v;
            sscanf(p, "%2x", &v);
            extra[n++] = (uint8_t)v;
        }
    }
    VrWma *w = vrwma_open(tag, ch, rate, br, align, bits, extra, n);
    if (!w) { fprintf(stderr, "open failed\n"); return 1; }
    FILE *f = fopen(argv[8], "rb");
    if (!f) return 1;
    uint8_t *buf = malloc((size_t)align);
    float *out = malloc(4u << 20);
    while (fread(buf, 1, (size_t)align, f) == (size_t)align) {
        int r = vrwma_decode(w, buf, align, out, 1 << 20);
        if (r > 0) fwrite(out, 4, (size_t)r, stdout);
        else if (r < 0) fprintf(stderr, "decode error %d\n", r);
    }
    vrwma_close(w);
    return 0;
}
