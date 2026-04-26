#define PY_SSIZE_T_CLEAN
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
