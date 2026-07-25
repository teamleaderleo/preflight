// Checks a conformance vector from BlockConformanceVector against whatever GPU is in this machine.
//
// The question is narrow and worth stating precisely: preflight encodes S3TC blocks offline, and
// every fidelity number it publishes comes from decoding those blocks with its own decoder. That is
// circular unless a real driver agrees. Apple's does, bit for bit. Whether NVIDIA's and AMD's do is
// unknown, and it is not a question documentation can settle: the S3TC specification defines the
// interpolated palette entries as weighted averages without pinning the rounding, so drivers are
// free to differ, and on Apple's driver the colour blends truncate while the alpha blends round --
// in the same block. That asymmetry is not derivable. It has to be measured per driver.
//
// This program deliberately does not link Java, LWJGL, or a window system. LWJGL's Pbuffer needs a
// display, which a rented headless GPU does not have; EGL's device platform does not. So the check
// splits: Java writes the vector on a machine with no GPU, this reads it on a GPU with no Java.
//
// Build:
//   Linux:  cc -O2 -o block-conformance-probe block-conformance-probe.c -lEGL -lGL
//   macOS:  cc -O2 -o block-conformance-probe block-conformance-probe.c -framework OpenGL \
//               -Wno-deprecated-declarations
//
// Run:  ./block-conformance-probe block-conformance-vector.bin
//
// Exit status is 0 when every case agrees within rounding, 1 on a mismatch, 2 if it could not run.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef __APPLE__
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl.h>
#else
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <GL/glext.h>
#endif

// Per-channel deviation above which a difference stops being rounding and starts being a layout bug.
// A layout error produces wholly different colours, not off-by-one ones, so this is generous on
// purpose: the interesting output is the measured worst deviation, not the pass flag.
#define ROUNDING_TOLERANCE 8

typedef void (*CompressedTexImage2DFn)(GLenum, GLint, GLenum, GLsizei, GLsizei, GLint, GLsizei,
                                       const void *);
static CompressedTexImage2DFn compressedTexImage2D = NULL;

// ---------------------------------------------------------------------------
// Context creation. Two backends, both headless, both giving a real desktop GL
// context rather than GL ES -- glGetTexImage does not exist in ES, and reading a
// compressed texture back as RGBA is the entire measurement.
// ---------------------------------------------------------------------------

#ifdef __APPLE__
static CGLContextObj appleContext;

static int createContext(void) {
    CGLPixelFormatAttribute attributes[] = {kCGLPFAAccelerated, kCGLPFAColorSize, 24, 0};
    CGLPixelFormatObj pixelFormat;
    GLint count;
    if (CGLChoosePixelFormat(attributes, &pixelFormat, &count) != kCGLNoError || !pixelFormat) {
        fprintf(stderr, "Could not choose a pixel format.\n");
        return 0;
    }
    CGLError error = CGLCreateContext(pixelFormat, NULL, &appleContext);
    CGLDestroyPixelFormat(pixelFormat);
    if (error != kCGLNoError) {
        fprintf(stderr, "Could not create a GL context: %d\n", error);
        return 0;
    }
    CGLSetCurrentContext(appleContext);
    compressedTexImage2D = (CompressedTexImage2DFn) glCompressedTexImage2D;
    return 1;
}

static void destroyContext(void) {
    CGLSetCurrentContext(NULL);
    CGLDestroyContext(appleContext);
}
#else
static EGLDisplay eglDisplayHandle = EGL_NO_DISPLAY;
static EGLContext eglContextHandle = EGL_NO_CONTEXT;

// A headless GPU has no X display, so the usual eglGetDisplay(EGL_DEFAULT_DISPLAY) finds nothing.
// EGL_EXT_platform_device enumerates the GPUs directly, which is how a container gets a context.
static EGLDisplay openHeadlessDisplay(void) {
    PFNEGLQUERYDEVICESEXTPROC queryDevices =
            (PFNEGLQUERYDEVICESEXTPROC) eglGetProcAddress("eglQueryDevicesEXT");
    PFNEGLGETPLATFORMDISPLAYEXTPROC getPlatformDisplay =
            (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (queryDevices && getPlatformDisplay) {
        EGLDeviceEXT devices[16];
        EGLint deviceCount = 0;
        if (queryDevices(16, devices, &deviceCount) && deviceCount > 0) {
            for (EGLint i = 0; i < deviceCount; i++) {
                EGLDisplay display =
                        getPlatformDisplay(EGL_PLATFORM_DEVICE_EXT, devices[i], NULL);
                if (display != EGL_NO_DISPLAY) {
                    EGLint major, minor;
                    if (eglInitialize(display, &major, &minor)) {
                        printf("EGL device %d of %d, EGL %d.%d\n", i, deviceCount, major, minor);
                        return display;
                    }
                }
            }
        }
    }
    // A desktop Linux box with a display server can still run this the ordinary way.
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major, minor;
    if (display != EGL_NO_DISPLAY && eglInitialize(display, &major, &minor)) {
        printf("EGL default display, EGL %d.%d\n", major, minor);
        return display;
    }
    return EGL_NO_DISPLAY;
}

static int createContext(void) {
    eglDisplayHandle = openHeadlessDisplay();
    if (eglDisplayHandle == EGL_NO_DISPLAY) {
        fprintf(stderr, "No EGL display. On a headless host this needs a driver with "
                        "EGL_EXT_platform_device (NVIDIA proprietary has it; Mesa needs surfaceless"
                        " and a usable render node).\n");
        return 0;
    }
    if (!eglBindAPI(EGL_OPENGL_API)) {
        fprintf(stderr, "This driver will not give a desktop GL context, only GL ES. "
                        "glGetTexImage does not exist there, so the readback cannot be done.\n");
        return 0;
    }
    EGLint configAttributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE,
                                 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                                 EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE};
    EGLConfig config;
    EGLint configCount = 0;
    if (!eglChooseConfig(eglDisplayHandle, configAttributes, &config, 1, &configCount)
            || configCount == 0) {
        fprintf(stderr, "No suitable EGL config.\n");
        return 0;
    }
    eglContextHandle = eglCreateContext(eglDisplayHandle, config, EGL_NO_CONTEXT, NULL);
    if (eglContextHandle == EGL_NO_CONTEXT) {
        fprintf(stderr, "Could not create a GL context (EGL error 0x%04X).\n", eglGetError());
        return 0;
    }
    EGLint surfaceAttributes[] = {EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(eglDisplayHandle, config, surfaceAttributes);
    if (!eglMakeCurrent(eglDisplayHandle, surface, surface, eglContextHandle)) {
        fprintf(stderr, "Could not make the context current (EGL error 0x%04X).\n", eglGetError());
        return 0;
    }
    compressedTexImage2D =
            (CompressedTexImage2DFn) eglGetProcAddress("glCompressedTexImage2D");
    return compressedTexImage2D != NULL;
}

static void destroyContext(void) {
    eglMakeCurrent(eglDisplayHandle, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglTerminate(eglDisplayHandle);
}
#endif

// ---------------------------------------------------------------------------
// Vector reading
// ---------------------------------------------------------------------------

static unsigned char *vectorBytes = NULL;
static size_t vectorSize = 0;
static size_t cursor = 0;

static int readInt(int *value) {
    if (cursor + 4 > vectorSize) {
        return 0;
    }
    *value = (int) ((unsigned) vectorBytes[cursor] | ((unsigned) vectorBytes[cursor + 1] << 8)
                    | ((unsigned) vectorBytes[cursor + 2] << 16)
                    | ((unsigned) vectorBytes[cursor + 3] << 24));
    cursor += 4;
    return 1;
}

static unsigned char *readBlob(int length) {
    if (length < 0 || cursor + (size_t) length > vectorSize) {
        return NULL;
    }
    unsigned char *blob = vectorBytes + cursor;
    cursor += (size_t) length;
    return blob;
}

static void drainErrors(void) {
    while (glGetError() != GL_NO_ERROR) {
        // Clear state left by earlier calls so the next read is unambiguous.
    }
}

static int checkCase(void) {
    int nameLength;
    if (!readInt(&nameLength)) {
        return -1;
    }
    unsigned char *nameBytes = readBlob(nameLength);
    if (!nameBytes) {
        return -1;
    }
    char name[128];
    int copied = nameLength < (int) sizeof(name) - 1 ? nameLength : (int) sizeof(name) - 1;
    memcpy(name, nameBytes, (size_t) copied);
    name[copied] = '\0';

    int format, width, height, blockLength;
    if (!readInt(&format) || !readInt(&width) || !readInt(&height) || !readInt(&blockLength)) {
        return -1;
    }
    unsigned char *blocks = readBlob(blockLength);
    if (!blocks) {
        return -1;
    }
    int expectedLength;
    if (!readInt(&expectedLength)) {
        return -1;
    }
    unsigned char *expected = readBlob(expectedLength);
    if (!expected || expectedLength != width * height * 4) {
        return -1;
    }

    GLuint texture = 0;
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    drainErrors();
    compressedTexImage2D(GL_TEXTURE_2D, 0, (GLenum) format, width, height, 0, blockLength, blocks);
    GLenum uploadError = glGetError();
    if (uploadError != GL_NO_ERROR) {
        printf("%-24s upload rejected, error=0x%04X -- format unsupported here.\n", name,
               uploadError);
        glDeleteTextures(1, &texture);
        return 0;  // Not a conformance failure: the driver simply does not have this format.
    }

    unsigned char *readback = malloc((size_t) expectedLength);
    if (!readback) {
        glDeleteTextures(1, &texture);
        return -1;
    }
    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, readback);
    GLenum readError = glGetError();
    glDeleteTextures(1, &texture);
    if (readError != GL_NO_ERROR) {
        printf("%-24s readback failed, error=0x%04X -- cannot compare.\n", name, readError);
        free(readback);
        return -1;
    }

    long identical = 0;
    long deviationSum = 0;
    int worst = 0;
    int worstPixel = -1;
    int pixels = width * height;
    for (int i = 0; i < pixels; i++) {
        int deviation = 0;
        for (int channel = 0; channel < 4; channel++) {
            int difference = readback[i * 4 + channel] - expected[i * 4 + channel];
            if (difference < 0) {
                difference = -difference;
            }
            if (difference > deviation) {
                deviation = difference;
            }
        }
        if (deviation == 0) {
            identical++;
        }
        if (deviation > worst) {
            worst = deviation;
            worstPixel = i;
        }
        deviationSum += deviation;
    }

    printf("%-24s pixels=%d  exact=%.2f%%  mean dev=%.3f  worst dev=%d\n", name, pixels,
           100.0 * (double) identical / pixels, (double) deviationSum / pixels, worst);
    if (worstPixel >= 0) {
        printf("%-24s   worst pixel (%d,%d): driver %02X%02X%02X%02X vs vector %02X%02X%02X%02X\n",
               "", worstPixel % width, worstPixel / width, readback[worstPixel * 4 + 3],
               readback[worstPixel * 4], readback[worstPixel * 4 + 1], readback[worstPixel * 4 + 2],
               expected[worstPixel * 4 + 3], expected[worstPixel * 4], expected[worstPixel * 4 + 1],
               expected[worstPixel * 4 + 2]);
    }
    free(readback);

    if (worst == 0) {
        printf("%-24s   identical -- layout and interpolation rounding both match.\n", "");
        return 1;
    }
    if (worst <= ROUNDING_TOLERANCE) {
        printf("%-24s   within the spec's interpolation tolerance; layout is correct, but this\n",
               "");
        printf("%-24s   driver rounds differently from the encoder's level tables.\n", "");
        return 1;
    }
    printf("%-24s   beyond rounding -- the layout does not match this driver.\n", "");
    return 0;
}

int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "block-conformance-vector.bin";
    FILE *file = fopen(path, "rb");
    if (!file) {
        fprintf(stderr, "Could not open %s\n", path);
        return 2;
    }
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    fseek(file, 0, SEEK_SET);
    if (size <= 12) {
        fprintf(stderr, "%s is too small to be a conformance vector.\n", path);
        fclose(file);
        return 2;
    }
    vectorBytes = malloc((size_t) size);
    if (!vectorBytes || fread(vectorBytes, 1, (size_t) size, file) != (size_t) size) {
        fprintf(stderr, "Could not read %s\n", path);
        fclose(file);
        return 2;
    }
    fclose(file);
    vectorSize = (size_t) size;

    if (memcmp(vectorBytes, "SPFV", 4) != 0) {
        fprintf(stderr, "%s is not a conformance vector.\n", path);
        return 2;
    }
    cursor = 4;
    int version, caseCount;
    if (!readInt(&version) || version != 1 || !readInt(&caseCount)) {
        fprintf(stderr, "Unsupported conformance vector version.\n");
        return 2;
    }

    if (!createContext()) {
        return 2;
    }
    printf("vendor:   %s\n", glGetString(GL_VENDOR));
    printf("renderer: %s\n", glGetString(GL_RENDERER));
    printf("version:  %s\n\n", glGetString(GL_VERSION));

    int failures = 0;
    for (int i = 0; i < caseCount; i++) {
        int result = checkCase();
        if (result < 0) {
            fprintf(stderr, "Conformance vector is malformed at case %d.\n", i);
            destroyContext();
            return 2;
        }
        if (result == 0) {
            failures++;
        }
    }
    printf("\n%s\n", failures == 0
            ? "VERDICT: this driver reads the blocks preflight writes."
            : "VERDICT: MISMATCH -- encoded blocks are not what this hardware reads.");
    destroyContext();
    free(vectorBytes);
    return failures == 0 ? 0 : 1;
}
