"""java.util.Random and String.hashCode, so random projections match the app's bit for bit.

The app seeds each block's projection with `Random(block.key.hashCode().toLong())`
(features/Similarity.kt); reproducing it here puts PC and phone songs in the same space.
"""

import math

_MULT = 0x5DEECE66D
_ADD = 0xB
_MASK = (1 << 48) - 1


def string_hash(s: str) -> int:
    """Java's String.hashCode: 31-polynomial over UTF-16 code units, as a signed 32-bit int."""
    h = 0
    data = s.encode("utf-16-be")
    for i in range(0, len(data), 2):
        h = (31 * h + ((data[i] << 8) | data[i + 1])) & 0xFFFFFFFF
    return h - (1 << 32) if h & 0x80000000 else h


class JavaRandom:
    def __init__(self, seed: int):
        self._seed = (seed ^ _MULT) & _MASK
        self._next_gaussian = None

    def _next(self, bits: int) -> int:
        self._seed = (self._seed * _MULT + _ADD) & _MASK
        r = self._seed >> (48 - bits)
        if bits == 32 and r & 0x80000000:
            r -= 1 << 32
        return r

    def next_double(self) -> float:
        return ((self._next(26) << 27) + self._next(27)) * (1.0 / (1 << 53))

    def next_gaussian(self) -> float:
        if self._next_gaussian is not None:
            g, self._next_gaussian = self._next_gaussian, None
            return g
        while True:
            v1 = 2 * self.next_double() - 1
            v2 = 2 * self.next_double() - 1
            s = v1 * v1 + v2 * v2
            if 0 < s < 1:
                break
        multiplier = math.sqrt(-2 * math.log(s) / s)
        self._next_gaussian = v2 * multiplier
        return v1 * multiplier
