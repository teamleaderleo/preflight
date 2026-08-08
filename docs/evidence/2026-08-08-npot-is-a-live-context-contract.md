# True-size textures are gated by the live OpenGL context

**Date:** 2026-08-08  
**Scope:** Windows, macOS, and Linux desktop OpenGL contexts  
**Result:** platform contract established; live Windows/Linux pilots remain release evidence

True-size texture uploads aren't a modern-JVM feature. The dimensions cross LWJGL unchanged and
are accepted or rejected by the current OpenGL context. Khronos made full non-power-of-two texture
support part of desktop OpenGL 2.0 in 2004, including ordinary 2D targets, mipmaps, and wrap modes:

- [OpenGL 2.0 specification, appendix I.3](https://registry.khronos.org/OpenGL/specs/gl/glspec20.pdf)
- [ARB_texture_non_power_of_two](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_texture_non_power_of_two.txt)

The first macOS probe went further than the specification and uploaded and read back a 597x373
texture on Starsector's legacy LWJGL 2 context. That established the reviewed Mac path. The same
OpenGL contract is platform-independent, though relying on the preset to imply it left one gap: a
driver or compatibility context below OpenGL 2.0 could still be told to use true-size textures.

`TexturePaddingRuntime` now reads the installation's own LWJGL 2 `ContextCapabilities` from the
context current on the texture-loading thread. It opens the true-size path only when either
`OpenGL20` or `GL_ARB_texture_non_power_of_two` is present. A missing current context is treated as
transient and retried later. A context that exposes neither capability, a missing LWJGL seam, or a
probe failure leaves Slick's original power-of-two fold in service.

Three independent conditions now have to agree:

1. the user-selected optimization preset requests true-size textures;
2. the exact reviewed fold bypass was installed successfully; and
3. the live OpenGL context advertises the required capability.

The run report records the result and whether it came from OpenGL 2.0 core or the ARB extension.
Unit fixtures cover core support, extension-only support, an unsupported context, and a context
that becomes current after the first query. This proves the cross-platform decision rule and its
fallback. A Windows and Linux visual smoke pilot is still required before calling the packaging,
driver, and end-to-end rendering paths field-tested on those platforms.
