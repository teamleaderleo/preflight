// Asks the machine's OpenGL driver what it can actually do with textures, rather than
// inferring it from vendor documentation or from the extension string alone.
//
// Every question here decides whether a proposed texture optimization is available at
// all on a given machine, and the answers are not portable: they depend on the GPU, the
// driver, and on macOS the OpenGL-over-Metal translation layer. Run it before believing
// any plan that assumes a compressed format exists.
//
// The legacy profile is the one that matters. Starsector uses LWJGL 2 and creates a
// compatibility-profile context, so the legacy numbers are the ones the game sees. The
// core profiles are probed only to show whether a capability is missing from the driver
// entirely or merely absent from the old profile.
//
// Build and run:  clang -o gl-capability-probe gl-capability-probe.c -framework OpenGL \
//                     -Wno-deprecated-declarations && ./gl-capability-probe
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl.h>
#include <OpenGL/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define GL_NUM_COMPRESSED_TEXTURE_FORMATS_ 0x86A2
#define GL_COMPRESSED_TEXTURE_FORMATS_ 0x86A3
#define GL_TEXTURE_COMPRESSED_ 0x86A1
#define GL_TEXTURE_COMPRESSED_IMAGE_SIZE_ 0x86A0
#define GL_TEXTURE_INTERNAL_FORMAT_ 0x1003

// The formats a texture-footprint plan might want, with their block sizes. BC1 and BC3
// are what the 2019 community discussion was about; BC4/BC5 are the correct home for
// normal and material maps; BC7 is the modern high-quality option, core in GL 4.2.
typedef struct {
    const char *name;
    GLint format;
    int bytesPerBlock;  // per 4x4 block; 0 if not a block format
} CompressedFormat;

static const CompressedFormat FORMATS[] = {
    {"GL_COMPRESSED_RGB_S3TC_DXT1   (BC1)", 0x83F0, 8},
    {"GL_COMPRESSED_RGBA_S3TC_DXT1  (BC1a)", 0x83F1, 8},
    {"GL_COMPRESSED_RGBA_S3TC_DXT3  (BC2)", 0x83F2, 16},
    {"GL_COMPRESSED_RGBA_S3TC_DXT5  (BC3)", 0x83F3, 16},
    {"GL_COMPRESSED_RED_RGTC1       (BC4)", 0x8DBB, 8},
    {"GL_COMPRESSED_RG_RGTC2        (BC5)", 0x8DBD, 16},
    {"GL_COMPRESSED_RGBA_BPTC_UNORM (BC7)", 0x8E8C, 16},
};
static const int FORMAT_COUNT = sizeof(FORMATS) / sizeof(FORMATS[0]);

static const char *EXTENSIONS[] = {
    "GL_EXT_texture_compression_s3tc",   "GL_ARB_texture_compression",
    "GL_ARB_texture_compression_bptc",   "GL_ARB_texture_compression_rgtc",
    "GL_ARB_texture_non_power_of_two",   "GL_KHR_texture_compression_astc_ldr",
    "GL_ARB_ES3_compatibility",          "GL_EXT_texture_sRGB",
    "GL_ARB_pixel_buffer_object",        "GL_APPLE_client_storage",
    "GL_APPLE_texture_range",            NULL};

static const int PROBE_WIDTH = 512;
static const int PROBE_HEIGHT = 512;

static void clearErrors(void) {
    while (glGetError() != GL_NO_ERROR) {
    }
}

static CGLContextObj makeContext(CGLOpenGLProfile profile) {
    CGLPixelFormatAttribute attributes[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)profile, kCGLPFAAccelerated,
        (CGLPixelFormatAttribute)0};
    CGLPixelFormatObj pixelFormat;
    GLint matching;
    if (CGLChoosePixelFormat(attributes, &pixelFormat, &matching) != kCGLNoError
            || pixelFormat == NULL) {
        return NULL;
    }
    CGLContextObj context;
    CGLError status = CGLCreateContext(pixelFormat, NULL, &context);
    CGLDestroyPixelFormat(pixelFormat);
    if (status != kCGLNoError) {
        return NULL;
    }
    CGLSetCurrentContext(context);
    return context;
}

/** Reports the driver's own claims: version, limits, and which extensions are named. */
static void reportClaims(const char *label, CGLOpenGLProfile profile, int coreProfile) {
    CGLContextObj context = makeContext(profile);
    if (context == NULL) {
        printf("== %s\n   unavailable on this machine\n\n", label);
        return;
    }
    printf("== %s\n", label);
    printf("   GL_VERSION    %s\n", glGetString(GL_VERSION));
    printf("   GL_RENDERER   %s\n", glGetString(GL_RENDERER));
    printf("   GL_VENDOR     %s\n", glGetString(GL_VENDOR));
    printf("   GLSL          %s\n", glGetString(GL_SHADING_LANGUAGE_VERSION));

    GLint maxTextureSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTextureSize);
    printf("   GL_MAX_TEXTURE_SIZE  %d\n", maxTextureSize);

    size_t capacity = 1 << 20;
    char *names = malloc(capacity);
    names[0] = '\0';
    if (coreProfile) {
        GLint count = 0;
        glGetIntegerv(GL_NUM_EXTENSIONS, &count);
        for (GLint i = 0; i < count; i++) {
            const char *name = (const char *)glGetStringi(GL_EXTENSIONS, i);
            if (name == NULL) {
                continue;
            }
            strlcat(names, name, capacity);
            strlcat(names, " ", capacity);
        }
    } else {
        const char *all = (const char *)glGetString(GL_EXTENSIONS);
        if (all != NULL) {
            strlcat(names, all, capacity);
            strlcat(names, " ", capacity);
        }
    }
    for (int i = 0; EXTENSIONS[i] != NULL; i++) {
        // Match the whole token: several extension names are prefixes of others.
        const char *hit = strstr(names, EXTENSIONS[i]);
        int present = hit != NULL && hit[strlen(EXTENSIONS[i])] == ' ';
        printf("   %-38s %s\n", EXTENSIONS[i], present ? "yes" : "no");
    }
    free(names);
    printf("\n");
    CGLSetCurrentContext(NULL);
    CGLDestroyContext(context);
}

/**
 * Reports what the driver does rather than what it says. An extension string is a claim;
 * an accepted upload is a fact, and the two have been known to disagree.
 */
static void reportUploads(void) {
    CGLContextObj context = makeContext(kCGLOGLPVersion_Legacy);
    if (context == NULL) {
        printf("== upload behaviour: no legacy context available\n");
        return;
    }
    size_t pixelBytes = (size_t)PROBE_WIDTH * PROBE_HEIGHT * 4;
    unsigned char *pixels = malloc(pixelBytes);
    for (size_t i = 0; i < pixelBytes; i++) {
        pixels[i] = (unsigned char)(i * 37);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    printf("== baseline: the upload Starsector performs today\n");
    {
        GLuint texture;
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        clearErrors();
        // TextureLoader hardcodes GL_RGBA as the internal format, so an opaque RGB source
        // is still resident at four bytes per pixel.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, PROBE_WIDTH, PROBE_HEIGHT, 0, GL_RGBA,
                GL_UNSIGNED_BYTE, pixels);
        GLenum error = glGetError();
        GLint stored = 0;
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT_, &stored);
        printf("   internalformat=GL_RGBA  error=0x%04X stored=0x%04X resident=%zu bytes\n\n",
                error, stored, pixelBytes);
        glDeleteTextures(1, &texture);
    }

    // Whether a compressed internal format alone is enough. If it is, the smallest
    // possible intervention is a single changed constant at the existing upload site,
    // with no asset pipeline, no encoder and no new file format.
    printf("== driver-side compression: glTexImage2D with a compressed internalformat\n");
    for (int i = 0; i < FORMAT_COUNT; i++) {
        GLuint texture;
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        clearErrors();
        glTexImage2D(GL_TEXTURE_2D, 0, FORMATS[i].format, PROBE_WIDTH, PROBE_HEIGHT, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        GLenum error = glGetError();
        GLint compressed = 0, size = 0;
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_COMPRESSED_, &compressed);
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_COMPRESSED_IMAGE_SIZE_, &size);
        printf("   %-38s error=0x%04X compressed=%-3s size=%-8d", FORMATS[i].name, error,
                compressed ? "yes" : "no", size);
        if (compressed && size > 0) {
            printf("%.2fx smaller", (double)pixelBytes / size);
        }
        printf("\n");
        glDeleteTextures(1, &texture);
    }

    // Whether pre-encoded blocks are accepted. This is the path that lets a good offline
    // encoder be used instead of whatever fast one the driver happens to ship.
    printf("\n== offline encoder path: glCompressedTexImage2D with pre-encoded blocks\n");
    for (int i = 0; i < FORMAT_COUNT; i++) {
        if (FORMATS[i].bytesPerBlock == 0) {
            continue;
        }
        size_t blockBytes =
                (size_t)(PROBE_WIDTH / 4) * (PROBE_HEIGHT / 4) * FORMATS[i].bytesPerBlock;
        unsigned char *blocks = calloc(blockBytes, 1);
        GLuint texture;
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        clearErrors();
        glCompressedTexImage2D(GL_TEXTURE_2D, 0, FORMATS[i].format, PROBE_WIDTH, PROBE_HEIGHT,
                0, (GLsizei)blockBytes, blocks);
        GLenum error = glGetError();
        printf("   %-38s error=0x%04X accepted=%s\n", FORMATS[i].name, error,
                error == GL_NO_ERROR ? "yes" : "no");
        glDeleteTextures(1, &texture);
        free(blocks);
    }

    // Whether the power-of-two padding the loader performs is required by this driver at
    // all. The dimensions are the real case observed against the running engine in
    // docs/evidence/2026-07-22-prepared-pixel-npot-padding.md.
    printf("\n== non-power-of-two: is the loader's get2Fold padding needed here?\n");
    {
        const int width = 597;
        const int height = 373;
        unsigned char *npot = calloc((size_t)width * height * 4, 1);
        GLuint texture;
        glGenTextures(1, &texture);
        glBindTexture(GL_TEXTURE_2D, texture);
        clearErrors();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                npot);
        GLenum error = glGetError();
        GLint storedWidth = 0, storedHeight = 0;
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, &storedWidth);
        glGetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, &storedHeight);
        printf("   %dx%d RGBA  error=0x%04X stored=%dx%d  (padded upload would be 1024x512)\n",
                width, height, error, storedWidth, storedHeight);
        glDeleteTextures(1, &texture);
        free(npot);
    }

    free(pixels);
    CGLSetCurrentContext(NULL);
    CGLDestroyContext(context);
}

int main(void) {
    reportClaims("Legacy profile (what Starsector's LWJGL 2 context gets)",
            kCGLOGLPVersion_Legacy, 0);
    reportClaims("Core 3.2 profile", kCGLOGLPVersion_GL3_Core, 1);
    reportClaims("Core 4.1 profile", kCGLOGLPVersion_GL4_Core, 1);
    reportUploads();
    return 0;
}
