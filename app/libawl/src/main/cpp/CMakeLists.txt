# libawl native helper: libawlspawn.so (fork+exec with an inherited fd).
# Plumbing ONLY — no binder, no wayland (the spawned clients stay pure and
# portable). The wayland fd handoff needs a real fork: Java's Runtime.exec
# cannot pass open fds to children.
cmake_minimum_required(VERSION 3.22.1)
project(awlspawn C)

add_library(awlspawn SHARED awlspawn_jni.c)
target_link_libraries(awlspawn PRIVATE log)

# Root helper for immersive mode: grabs the input devices (EVIOCGRAB) in the
# root context and streams their evdev events to the app over a bridge socket.
# Built as an executable named lib*.so so the packager extracts it into
# nativeLibraryDir executable (needs useLegacyPackaging=true); the app runs it
# via `su -c` because untrusted_app cannot read /dev/input itself.
add_executable(inputgrab input_grab.c socket_utils.c)
set_target_properties(inputgrab PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "inputgrab"
    SUFFIX ".so")
target_link_libraries(inputgrab PRIVATE log)
