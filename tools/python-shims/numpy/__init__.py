class _DType(str):
    pass


uint8 = _DType("uint8")
int32 = _DType("int32")


def _shape_size(shape):
    size = 1
    for item in shape:
        size *= int(item)
    return size


class _Flags(dict):
    def __init__(self):
        super().__init__({"C_CONTIGUOUS": True})


class _Ctypes:
    data = 0


class ndarray:
    def __init__(self, shape=(), dtype=None, data=None):
        if isinstance(shape, int):
            shape = (shape,)
        self.shape = tuple(shape)
        self.dtype = dtype
        self._data = list(data) if data is not None else None
        self.flags = _Flags()
        self.ctypes = _Ctypes()

    @property
    def ndim(self):
        return len(self.shape)

    @property
    def size(self):
        return len(self._data) if self._data is not None else _shape_size(self.shape)

    def __len__(self):
        return self.shape[0] if self.shape else 0

    def __getitem__(self, index):
        if self._data is None:
            raise IndexError("array data is not materialized")
        return self._data[index]

    def __iter__(self):
        return iter(self._data or [])


def array(value, dtype=None):
    data = list(value)
    return ndarray((len(data),), dtype=dtype, data=data)


def ascontiguousarray(value):
    return value if isinstance(value, ndarray) else array(value)


class _CtypesLib:
    @staticmethod
    def as_array(_pointer, shape=None):
        return ndarray(shape or (), dtype=uint8)


ctypeslib = _CtypesLib()
