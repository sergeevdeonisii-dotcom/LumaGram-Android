"""Compile and draw the actual Java GLSL in desktop offscreen GLES2 (no Android device).

This validates shader pixels, not Android Camera2/MediaCodec or hardware performance.
Run with --egl-dir pointing to an existing SwiftShader/ANGLE libEGL/libGLESv2 folder.
"""
import argparse
import ctypes as c
import json
import os
from pathlib import Path
import re

parser = argparse.ArgumentParser()
parser.add_argument("--egl-dir", required=True)
args = parser.parse_args()
dll_directory = os.add_dll_directory(str(Path(args.egl_dir).resolve()))
egl = c.WinDLL(str(Path(args.egl_dir) / "libEGL.dll"))
gl = c.WinDLL(str(Path(args.egl_dir) / "libGLESv2.dll"))
def bind(lib, name, result, *types):
    f = getattr(lib, name)
    f.restype, f.argtypes = result, types
    return f
ptr, integer, uint, real = c.c_void_p, c.c_int, c.c_uint, c.c_float
get_display = bind(egl, "eglGetDisplay", ptr, ptr)
initialize = bind(egl, "eglInitialize", uint, ptr, c.POINTER(integer), c.POINTER(integer))
choose = bind(egl, "eglChooseConfig", uint, ptr, c.POINTER(integer), c.POINTER(ptr), integer, c.POINTER(integer))
pbuffer = bind(egl, "eglCreatePbufferSurface", ptr, ptr, ptr, c.POINTER(integer))
context = bind(egl, "eglCreateContext", ptr, ptr, ptr, ptr, c.POINTER(integer))
make_current = bind(egl, "eglMakeCurrent", uint, ptr, ptr, ptr, ptr)
display = get_display(None)
major, minor = integer(), integer()
assert initialize(display, c.byref(major), c.byref(minor)), "EGL initialization failed"
attributes = (integer * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
config, count = ptr(), integer()
assert choose(display, attributes, c.byref(config), 1, c.byref(count)) and count.value, "No GLES2 pbuffer config"
surface = pbuffer(display, config, (integer * 5)(0x3057, 64, 0x3056, 64, 0x3038))
ctx = context(display, config, None, (integer * 3)(0x3098, 2, 0x3038))
assert surface and ctx and make_current(display, surface, surface, ctx), "GLES2 context failed"
source = (Path(__file__).resolve().parents[3] / "TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoRetroRenderer.java").read_text(encoding="utf-8")
def shader_source(name):
    block = re.search(r"static final String " + name + r"\s*=\s*(.*?);", source, re.S)
    assert block, name
    # Semicolons occur inside GLSL literals, so collect up to the Java statement boundary.
    start = source.index("static final String " + name)
    end = source.index(";\n", start)
    return "".join(json.loads('"' + s + '"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"', source[start:end]))
create_shader = bind(gl, "glCreateShader", uint, uint)
shader_text = bind(gl, "glShaderSource", None, uint, integer, c.POINTER(c.c_char_p), c.POINTER(integer))
compile_shader = bind(gl, "glCompileShader", None, uint)
shader_status = bind(gl, "glGetShaderiv", None, uint, uint, c.POINTER(integer))
shader_log = bind(gl, "glGetShaderInfoLog", None, uint, integer, c.POINTER(integer), c.c_char_p)
def compile_source(kind, text):
    shader = create_shader(kind)
    data = c.c_char_p(text.encode())
    shader_text(shader, 1, c.byref(data), None)
    compile_shader(shader)
    status = integer()
    shader_status(shader, 0x8B81, c.byref(status))
    log = c.create_string_buffer(4096)
    shader_log(shader, 4096, None, log)
    assert status.value, log.value.decode()
    return shader
vertex = compile_source(0x8B31, shader_source("VERTEX"))
fragment = compile_source(0x8B30, shader_source("FRAGMENT"))
program = bind(gl, "glCreateProgram", uint)()
attach = bind(gl, "glAttachShader", None, uint, uint)
attach(program, vertex); attach(program, fragment)
bind(gl, "glLinkProgram", None, uint)(program)
status = integer()
bind(gl, "glGetProgramiv", None, uint, uint, c.POINTER(integer))(program, 0x8B82, c.byref(status))
assert status.value, "Program link failed"
bind(gl, "glUseProgram", None, uint)(program)
get_uniform = bind(gl, "glGetUniformLocation", integer, uint, c.c_char_p)
u1i = bind(gl, "glUniform1i", None, integer, integer)
u1f = bind(gl, "glUniform1f", None, integer, real)
u2f = bind(gl, "glUniform2f", None, integer, real, real)
def uniform(name): return get_uniform(program, name.encode())
textures = (uint * 2)()
bind(gl, "glGenTextures", None, integer, c.POINTER(uint))(2, textures)
active = bind(gl, "glActiveTexture", None, uint)
bind_texture = bind(gl, "glBindTexture", None, uint, uint)
parameter = bind(gl, "glTexParameteri", None, uint, uint, integer)
image = bind(gl, "glTexImage2D", None, uint, integer, integer, integer, integer, integer, uint, uint, ptr)
pixels = bytearray()
stamp = bytearray()
for y in range(64):
    for x in range(64):
        pixels.extend((x * 4, y * 4, (x * 3 + y * 2) % 256, 255))
        stamp.extend((255, 194, 72, 255 if 20 <= x < 44 and 20 <= y < 44 else 0))
for unit, data in enumerate((pixels, stamp)):
    active(0x84C0 + unit); bind_texture(0x0DE1, textures[unit])
    for key, value in ((0x2801, 0x2600), (0x2800, 0x2600), (0x2802, 0x812F), (0x2803, 0x812F)):
        parameter(0x0DE1, key, value)
    buffer = (c.c_ubyte * len(data)).from_buffer_copy(data)
    image(0x0DE1, 0, 0x1908, 64, 64, 0, 0x1908, 0x1401, buffer)
u1i(uniform("frame"), 0); u1i(uniform("stamp"), 1)
u2f(uniform("pixel"), 1 / 64, 1 / 64)
position = bind(gl, "glGetAttribLocation", integer, uint, c.c_char_p)(program, b"position")
vertices = (real * 8)(-1, -1, 1, -1, -1, 1, 1, 1)
bind(gl, "glEnableVertexAttribArray", None, uint)(position)
bind(gl, "glVertexAttribPointer", None, uint, integer, uint, uint, integer, ptr)(position, 2, 0x1406, 0, 0, vertices)
bind(gl, "glViewport", None, integer, integer, integer, integer)(0, 0, 64, 64)
draw = bind(gl, "glDrawArrays", None, uint, integer, integer)
read = bind(gl, "glReadPixels", None, integer, integer, integer, integer, uint, uint, ptr)
def render(mode, date=0, tick=1):
    u1f(uniform("mode"), mode); u1f(uniform("dateEnabled"), date); u1f(uniform("tick"), tick)
    draw(5, 0, 4)
    output = (c.c_ubyte * (64 * 64 * 4))()
    read(0, 0, 64, 64, 0x1908, 0x1401, output)
    assert bind(gl, "glGetError", uint)() == 0, "GLES error"
    return bytes(output)
original = render(0)
assert original == bytes(pixels), "Original effect changed pixels"
for effect, label in ((1, "VHS"), (2, "grain"), (3, "monochrome"), (4, "digital")):
    result = render(effect)
    changes = sum(a != b for a, b in zip(result, original))
    assert changes > 4096, (label, changes)
    if effect == 3:
        assert all(result[i] == result[i + 1] == result[i + 2] for i in range(0, len(result), 4)), "Monochrome channel mismatch"
    print(f"PASS {label}: {changes} altered components")
assert render(2, tick=1) != render(2, tick=2), "Grain does not advance"
dated = render(0, date=1)
center = (32 * 64 + 32) * 4
assert dated[center:center + 4] == bytes((255, 194, 72, 255)), "Date alpha compositing failed"
assert dated[:4] == original[:4], "Date damaged transparent area"
print("PASS GLES2 actual production shader: original identity, four distinct effects, moving grain, date compositing")
make_current(display, None, None, None)
bind(egl, "eglDestroyContext", uint, ptr, ptr)(display, ctx)
bind(egl, "eglDestroySurface", uint, ptr, ptr)(display, surface)
bind(egl, "eglTerminate", uint, ptr)(display)
