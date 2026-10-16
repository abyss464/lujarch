"""A blue-noise threshold tile by void and cluster (Ulichney 1993), deterministic for a seed.

The boot animation's surface is RGB_565: frames are dithered to its levels with this tile (screen
space, the same in every frame). Blue noise puts the dither's error at high spatial frequencies,
where it does not read as grain or as a pattern; a tile repeats along a row, which also lets
deflate match a dithered gradient row against itself.
"""
import numpy as np


def _kernel(n, sigma):
    d = np.minimum(np.arange(n), n - np.arange(n)).astype(np.float64)
    return np.exp(-(d[:, None] ** 2 + d[None, :] ** 2) / (2 * sigma * sigma))


def _shifted(k, p, n):
    return np.roll(np.roll(k, p // n, axis=0), p % n, axis=1)


def blue_noise(n, sigma=1.5, seed=106):
    """Thresholds in (0, 1), shape (n, n): rank r of n*n becomes (r + 0.5) / n^2."""
    size = n * n
    k = _kernel(n, sigma)
    fk = np.fft.rfft2(k)
    energy_of = lambda b: np.fft.irfft2(np.fft.rfft2(b) * fk, s=(n, n))
    rng = np.random.default_rng(seed)
    pattern = np.zeros(size, bool)
    pattern[rng.choice(size, size // 10, replace=False)] = True

    # Initial pattern: move the tightest cluster to the largest void until that is the same pixel.
    e = energy_of(pattern.reshape(n, n)).ravel()
    while True:
        c = int(np.argmax(np.where(pattern, e, -np.inf)))
        pattern[c] = False; e -= _shifted(k, c, n).ravel()
        v = int(np.argmin(np.where(pattern, np.inf, e)))
        pattern[v] = True; e += _shifted(k, v, n).ravel()
        if v == c:
            break
    ones = int(pattern.sum())
    rank = np.empty(size, np.int64)

    # Phase 1: take the initial pattern's ones out, tightest cluster first.
    p = pattern.copy(); ep = e.copy()
    for r in range(ones - 1, -1, -1):
        c = int(np.argmax(np.where(p, ep, -np.inf)))
        p[c] = False; ep -= _shifted(k, c, n).ravel(); rank[c] = r
    # Phase 2: fill the largest voids up to half.
    p = pattern.copy(); ep = e.copy()
    for r in range(ones, size // 2):
        v = int(np.argmin(np.where(p, np.inf, ep)))
        p[v] = True; ep += _shifted(k, v, n).ravel(); rank[v] = r
    # Phase 3: the zeros are the minority now: take their tightest cluster (energy of the zeros).
    ez = energy_of((~p).reshape(n, n)).ravel()
    for r in range(size // 2, size):
        c = int(np.argmax(np.where(p, -np.inf, ez)))
        p[c] = True; ez -= _shifted(k, c, n).ravel(); rank[c] = r
    return ((rank + 0.5) / size).reshape(n, n)


if __name__ == "__main__":
    import sys
    t = blue_noise(int(sys.argv[1]) if len(sys.argv) > 1 else 16)
    f = np.abs(np.fft.fft2(t - t.mean())) ** 2
    n = t.shape[0]
    fy, fx = np.meshgrid(np.fft.fftfreq(n), np.fft.fftfreq(n), indexing="ij")
    r = np.hypot(fx, fy)
    low, high = f[(r > 0) & (r < 0.15)].mean(), f[r > 0.35].mean()
    print(f"{n}x{n}, thresholds {t.min():.5f}..{t.max():.5f}, unique {len(np.unique(t))}, "
          f"power low/high frequency {low / high:.3f}")
