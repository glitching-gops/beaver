#!/usr/bin/env python3
"""
Build an offline Sentinel-2 true-colour basemap (.mbtiles) for SpatialMapper's MapLibre PoC.

DESKTOP TOOL. Run by a developer on a PC; nothing here ships in the app. The output file is copied to
the phone with `adb push` and rendered by the debug-only MapLibre PoC screen with no network access.

Pipeline
--------
1. SEARCH   Sentinel-2 L2A scenes intersecting the bounding box in the date range, with scene-level
            cloud cover <= --max-cloud, from Element 84's Earth Search STAC API.
2. SELECT   The least-cloudy scene *over the bounding box*, not just scene-wide (see below).
3. READ     Only the pixels covering the bbox (+ a small margin) from the B04/B03/B02 10 m
            Cloud-Optimised GeoTIFFs, via HTTP range requests. Nothing close to a full 110 km tile
            is downloaded.
4. STRETCH  Reflectance -> 8-bit true colour with a percentile contrast stretch and gamma.
5. WARP     Straight from the scene's UTM grid to each zoom level's Web Mercator (EPSG:3857) tile
            grid, one resampling step per zoom, then clip to the bbox (outside = transparent).
6. PACK     256 px raster tiles into a single MBTiles file.

Imagery source: Element 84 Earth Search, collection `sentinel-2-c1-l2a`
----------------------------------------------------------------------
- NO AUTHENTICATION. The STAC API is public and the COGs sit in a public AWS Open Data bucket;
  plain HTTPS range reads work with no account, key or signing. Copernicus Data Space Ecosystem
  has the same data but needs registration plus an OAuth client-credentials flow, which buys
  nothing for a one-off desktop build.
- `sentinel-2-c1-l2a` ("Collection 1") rather than the older `sentinel-2-l2a`: it is ESA's
  consistently reprocessed archive. Both are on Earth Search; Collection 1 is the maintained one.
- Reflectance scale/offset come from each asset's `raster:bands` metadata rather than being
  hard-coded. Since processing baseline 04.00 the L2A digital numbers carry a -0.1 offset, and
  reading it per item keeps older and newer scenes correct alike.
- The bands are B04 (red), B03 (green) and B02 (blue), all at 10 m. The pre-rendered `visual`
  (TCI) asset is deliberately not used: it is a fixed 0-0.2 reflectance clip, which renders
  vegetation dark and burns out bright soil and roofs.

"Least cloudy" means least cloudy over YOUR bbox
------------------------------------------------
`eo:cloud_cover` describes the whole ~110 x 110 km MGRS tile. A 3 % scene can have its only cloud
parked over a 2 km field, and a 40 % scene can be clear there. So the lowest-cloud candidates by
scene cover (--candidates of them) have their 20 m Scene Classification Layer (SCL) read over the
bbox, and each is scored by the fraction of unusable pixels: no data (incomplete coverage),
saturated, cloud shadow, medium/high-probability cloud, and thin cirrus. Ranking is:
  1. lowest unusable fraction over the bbox (to 0.1 %),
  2. then lowest scene-wide cloud (to 1 %), as a proxy for haze the SCL does not flag,
  3. then the most recent acquisition, because newer imagery better matches the trees on the ground.
The chosen scene id is printed, together with a `--scene` command that reproduces the exact build.

Zoom range: z10-z14 by default
------------------------------
Web Mercator ground resolution for 256 px tiles is 156543.03 * cos(lat) / 2^z metres per pixel.
At this project's latitude (~13 deg N):
    z13 = 18.6 m/px    z14 = 9.3 m/px    z15 = 4.7 m/px    z16 = 2.3 m/px
Sentinel-2 is 10 m, so z14 is effectively native resolution. A baked z15 tile would be the z14
data upsampled 2x: no extra detail, 4x the tiles. MapLibre over-zooms a raster source past its
maxzoom on its own, so the map still zooms in to z18 over this archive and looks identical to what
a baked z15/z16 would have shown, at none of the file-size cost. z10 (~150 m/px) is the floor:
below it a field-sized bbox is a few pixels wide. For a small bbox the low zooms cost about one
tile each.

Output format: MBTiles (checked against MapLibre Native Android 13.6.1, not assumed)
-----------------------------------------------------------------------------------
Both MBTiles and PMTiles file sources are compiled into the 13.6.1 AAR's libmaplibre.so, so both
would load. MBTiles is the better fit here:
- `mbtiles:///absolute/path.mbtiles` is a first-class local-file URL. MapLibre opens the SQLite
  file read-only and looks tiles up by (z, x, y) directly
  (platform/default/src/mln/storage/mbtiles_file_source.cpp at tag android-v13.6.1).
- The Android PoC reads this file's `metadata` table with the platform's own
  android.database.sqlite, with no parser and no dependency, to show the scene and the
  attribution year on screen. PMTiles metadata sits behind a binary header and a
  gzip-compressed JSON blob.
- The writer here is Python's standard-library sqlite3, so there is no extra dependency.
PMTiles' advantage, serving a single archive over plain HTTP range requests, doesn't matter for
a file that is side-loaded with adb.

MapLibre builds the source's TileJSON from the metadata table. This script therefore writes
`bounds` (exactly 4 comma-separated numbers, or MapLibre rejects the source), `minzoom`,
`maxzoom`, `format` and `attribution`, and deliberately no `center` key, because MapLibre derives
that from `bounds` itself. Tile rows are stored TMS-flipped as the MBTiles spec requires;
MapLibre flips them back.

Attribution
-----------
Copernicus terms require "Contains modified Copernicus Sentinel data [YEAR]", where YEAR is the
acquisition year of the data used. The script writes exactly that string, with the selected
scene's year, into the MBTiles `attribution` metadata, and the Android PoC displays it
permanently on the map.

Setup (Windows, PowerShell), from the repository root
-----------------------------------------------------
    py -3 -m venv tools\\basemap\\.venv
    tools\\basemap\\.venv\\Scripts\\Activate.ps1
    python -m pip install --upgrade pip
    python -m pip install -r tools\\basemap\\requirements.txt

(If Activate.ps1 is blocked by execution policy, run the venv's python.exe directly:
 tools\\basemap\\.venv\\Scripts\\python.exe tools\\basemap\\make_sentinel2_mbtiles.py ...)

Usage
-----
    python tools\\basemap\\make_sentinel2_mbtiles.py `
        --bbox 13.1175 77.7996 13.1375 77.8196 `
        --start 2025-11-01 --end 2026-05-31 --max-cloud 20

--bbox is SOUTH WEST NORTH EAST: latitude/longitude order, the way coordinates are read off a map.
Output defaults to tools\\basemap\\out\\sentinel2_basemap.mbtiles (git-ignored), plus a
.preview.png of the full-resolution clip for a quick visual check.
"""
from __future__ import annotations

import argparse
import io
import math
import os
import sqlite3
import sys
import time
import warnings
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import rasterio
from PIL import Image
from pystac_client import Client
from rasterio.enums import Resampling
from rasterio.transform import from_bounds as transform_from_bounds
from rasterio.warp import reproject, transform_bounds
from rasterio.windows import Window
from rasterio.windows import from_bounds as window_from_bounds

STAC_API = "https://earth-search.aws.element84.com/v1"
COLLECTION = "sentinel-2-c1-l2a"
RGB_ASSETS = ("red", "green", "blue")  # B04, B03, B02, all 10 m
SCL_ASSET = "scl"  # Scene Classification Layer, 20 m

# SCL classes that make a pixel unusable in a basemap: 0 no data, 1 saturated/defective,
# 3 cloud shadow, 8 cloud (medium probability), 9 cloud (high probability), 10 thin cirrus.
SCL_UNUSABLE = (0, 1, 3, 8, 9, 10)

TILE_SIZE = 256
NATIVE_RES_M = 10.0
EARTH_RADIUS_M = 6378137.0
ORIGIN_SHIFT_M = math.pi * EARTH_RADIUS_M  # half the Web Mercator world width, 20037508.34 m

# Reading beyond the bbox so that bilinear/average resampling at its edges uses real pixels.
READ_MARGIN_M = 100.0

DEFAULT_OUTPUT = Path(__file__).resolve().parent / "out" / "sentinel2_basemap.mbtiles"

# pystac tries to migrate Earth Search's asset hrefs into its storage extension and warns, once per
# item, that it can't parse an S3 bucket from them. Harmless (the hrefs are plain HTTPS and are read
# as such); silenced so it cannot be mistaken for a real problem in the output.
warnings.filterwarnings("ignore", message="Could not parse bucket/account from href", category=UserWarning)

# COG reads over HTTPS: skip the directory listing GDAL would otherwise attempt, never sign
# requests (the bucket is public), and retry transient HTTP failures.
GDAL_ENV = {
    "GDAL_DISABLE_READDIR_ON_OPEN": "EMPTY_DIR",
    "CPL_VSIL_CURL_ALLOWED_EXTENSIONS": ".tif",
    "AWS_NO_SIGN_REQUEST": "YES",
    "GDAL_HTTP_MAX_RETRY": "5",
    "GDAL_HTTP_RETRY_DELAY": "2",
}


def log(message: str = "") -> None:
    print(message, flush=True)


# ---------------------------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class BBox:
    south: float
    west: float
    north: float
    east: float

    @property
    def wsen(self) -> list[float]:
        """GeoJSON/STAC order: west, south, east, north."""
        return [self.west, self.south, self.east, self.north]

    @property
    def center_lat(self) -> float:
        return (self.south + self.north) / 2

    def size_km(self) -> tuple[float, float]:
        width = math.radians(self.east - self.west) * EARTH_RADIUS_M * math.cos(math.radians(self.center_lat))
        height = math.radians(self.north - self.south) * EARTH_RADIUS_M
        return width / 1000, height / 1000


def lonlat_to_3857(lon: float, lat: float) -> tuple[float, float]:
    x = math.radians(lon) * EARTH_RADIUS_M
    y = math.asinh(math.tan(math.radians(lat))) * EARTH_RADIUS_M
    return x, y


def lonlat_to_tile_fraction(lon: float, lat: float, z: int) -> tuple[float, float]:
    n = 2**z
    x = (lon + 180.0) / 360.0 * n
    y = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n
    return x, y


def tile_range(bbox: BBox, z: int) -> tuple[int, int, int, int]:
    """Inclusive XYZ tile range (x0, y0, x1, y1) covering the bbox at zoom z."""
    n = 2**z
    fx0, fy0 = lonlat_to_tile_fraction(bbox.west, bbox.north, z)
    fx1, fy1 = lonlat_to_tile_fraction(bbox.east, bbox.south, z)
    x0, y0 = int(math.floor(fx0)), int(math.floor(fy0))
    # ceil(...) - 1 so an edge lying exactly on a tile boundary does not pull in an empty tile.
    x1 = min(max(int(math.ceil(fx1)) - 1, x0), n - 1)
    y1 = min(max(int(math.ceil(fy1)) - 1, y0), n - 1)
    return x0, y0, x1, y1


def tile_origin_3857(x: int, y: int, z: int) -> tuple[float, float]:
    """Top-left corner of XYZ tile (x, y, z) in Web Mercator metres."""
    size = 2 * ORIGIN_SHIFT_M / 2**z
    return -ORIGIN_SHIFT_M + x * size, ORIGIN_SHIFT_M - y * size


def ground_resolution_m(z: int, lat: float) -> float:
    return 2 * ORIGIN_SHIFT_M / (TILE_SIZE * 2**z) * math.cos(math.radians(lat))


# ---------------------------------------------------------------------------------------------
# Scene search and selection
# ---------------------------------------------------------------------------------------------


@dataclass
class Candidate:
    item: object  # pystac.Item
    scene_cloud: float
    bbox_unusable: float = 1.0

    @property
    def id(self) -> str:
        return self.item.id

    @property
    def date(self) -> str:
        return self.item.datetime.strftime("%Y-%m-%d")


def read_bbox_window(href: str, bbox: BBox, margin_m: float) -> tuple[np.ndarray, object, object, float]:
    """
    Read the pixels of a single-band COG that cover the bbox (+ margin), in the file's own CRS.

    Returns (array, window transform, crs, coverage). coverage is the fraction of the requested
    window that lies inside the file: below 1.0, the bbox runs off the edge of this MGRS tile.
    """
    with rasterio.open(href) as src:
        left, bottom, right, top = transform_bounds(
            "EPSG:4326", src.crs, bbox.west, bbox.south, bbox.east, bbox.north, densify_pts=21
        )
        wanted = window_from_bounds(
            left - margin_m, bottom - margin_m, right + margin_m, top + margin_m, transform=src.transform
        )
        col0, row0 = math.floor(wanted.col_off), math.floor(wanted.row_off)
        col1 = math.ceil(wanted.col_off + wanted.width)
        row1 = math.ceil(wanted.row_off + wanted.height)
        wanted_px = (col1 - col0) * (row1 - row0)

        col0c, row0c = max(col0, 0), max(row0, 0)
        col1c, row1c = min(col1, src.width), min(row1, src.height)
        if col1c <= col0c or row1c <= row0c:
            return np.zeros((0, 0), dtype=src.dtypes[0]), None, src.crs, 0.0

        window = Window(col0c, row0c, col1c - col0c, row1c - row0c)
        data = src.read(1, window=window)
        coverage = (window.width * window.height) / wanted_px
        return data, src.window_transform(window), src.crs, coverage


def search_candidates(bbox: BBox, start: str, end: str, max_cloud: float) -> list[Candidate]:
    client = Client.open(STAC_API)
    search = client.search(
        collections=[COLLECTION],
        bbox=bbox.wsen,
        datetime=f"{start}/{end}",
        query={"eo:cloud_cover": {"lte": max_cloud}},
        max_items=1000,
    )
    items = list(search.items())
    candidates = [Candidate(item, float(item.properties.get("eo:cloud_cover", 100.0))) for item in items]
    candidates.sort(key=lambda c: (c.scene_cloud, -c.item.datetime.timestamp()))
    return candidates


def score_bbox_cloud(candidate: Candidate, bbox: BBox) -> None:
    """Fraction of the bbox that is unusable in this scene, from its SCL (incl. missing coverage)."""
    scl, _, _, coverage = read_bbox_window(candidate.item.assets[SCL_ASSET].href, bbox, margin_m=0.0)
    if scl.size == 0:
        candidate.bbox_unusable = 1.0
        return
    unusable_in_file = float(np.isin(scl, SCL_UNUSABLE).mean())
    # Pixels of the bbox that fall outside this MGRS tile are unusable too.
    candidate.bbox_unusable = 1.0 - coverage * (1.0 - unusable_in_file)


def select_scene(candidates: list[Candidate], bbox: BBox, max_checked: int) -> Candidate:
    checked = candidates[:max_checked]
    log(f"Scoring cloud over the bbox for the {len(checked)} lowest scene-cloud candidates (SCL, 20 m):")
    for c in checked:
        try:
            score_bbox_cloud(c, bbox)
        except rasterio.errors.RasterioIOError as error:
            log(f"  {c.id}: could not read SCL ({error}); skipped")
            c.bbox_unusable = 1.0
            continue
        log(f"  {c.id}  {c.date}  scene cloud {c.scene_cloud:6.2f} %  unusable over bbox {c.bbox_unusable * 100:6.2f} %")

    best = min(
        checked,
        key=lambda c: (round(c.bbox_unusable, 3), round(c.scene_cloud), -c.item.datetime.timestamp()),
    )
    if best.bbox_unusable >= 0.999:
        sys.exit("No candidate scene covers the bbox with usable pixels. Widen the date range or raise --max-cloud.")
    return best


# ---------------------------------------------------------------------------------------------
# Imagery
# ---------------------------------------------------------------------------------------------


@dataclass
class SceneRaster:
    rgba: np.ndarray  # (4, h, w) uint8 in the scene's UTM grid; band 4 is validity alpha
    transform: object
    crs: object
    stretch_note: str


def band_scale_offset(asset) -> tuple[float, float, int]:
    """(scale, offset, nodata) from the asset's raster:bands metadata, with L2A defaults."""
    bands = asset.extra_fields.get("raster:bands") or [{}]
    band = bands[0]
    return float(band.get("scale", 0.0001)), float(band.get("offset", 0.0)), int(band.get("nodata", 0))


def read_true_colour(item, bbox: BBox, low_pct: float, high_pct: float, gamma: float) -> SceneRaster:
    reflectance = []
    valid = None
    transform = crs = None
    for key in RGB_ASSETS:
        asset = item.assets[key]
        dn, transform, crs, coverage = read_bbox_window(asset.href, bbox, READ_MARGIN_M)
        if coverage < 1.0:
            log(f"  warning: {key} band covers only {coverage * 100:.1f} % of the requested window")
        scale, offset, nodata = band_scale_offset(asset)
        band_valid = dn != nodata
        valid = band_valid if valid is None else (valid & band_valid)
        reflectance.append(dn.astype(np.float32) * scale + offset)
        log(f"  read {key:<5} {dn.shape[1]} x {dn.shape[0]} px  (scale {scale}, offset {offset})")

    refl = np.stack(reflectance)
    if not valid.any():
        sys.exit("Selected scene has no valid pixels over the bbox.")

    # Contrast stretch, colour-preserving:
    #  - a per-band black point (the low percentile) removes each band's dark floor, including the
    #    residual path-radiance haze that lifts blue, so shadows become neutral rather than blue-grey;
    #  - ONE shared white point across all three bands (the highest band's high percentile after the
    #    black point) keeps the ratios between bands, i.e. the hue, instead of white-balancing
    #    every band to full range, which paints the whole scene toward grey;
    #  - a gamma > 1 lifts the mid-tones, where vegetation lives. Raw L2A true colour is dark and
    #    flat because canopy reflectance in the visible bands is only ~0.02-0.08.
    lows = np.array([np.percentile(refl[b][valid], low_pct) for b in range(3)], dtype=np.float32)
    shifted = refl - lows[:, None, None]
    white = float(max(np.percentile(shifted[b][valid], high_pct) for b in range(3)))
    white = max(white, 1e-6)
    stretched = np.clip(shifted / white, 0.0, 1.0) ** (1.0 / gamma)

    rgba = np.zeros((4,) + valid.shape, dtype=np.uint8)
    rgba[:3] = np.round(stretched * 255.0).astype(np.uint8)
    rgba[:3, ~valid] = 0
    rgba[3] = np.where(valid, 255, 0).astype(np.uint8)

    note = (
        f"black points R/G/B {lows[0]:.4f}/{lows[1]:.4f}/{lows[2]:.4f} (p{low_pct:g}), "
        f"shared white +{white:.4f} (p{high_pct:g}), gamma {gamma:g}"
    )
    return SceneRaster(rgba, transform, crs, note)


def render_zoom(scene: SceneRaster, bbox: BBox, z: int) -> tuple[np.ndarray, tuple[int, int, int, int]]:
    """
    Warp the scene onto the zoom-z tile grid covering the bbox, clipped to the bbox.
    Returns an RGBA canvas (4, H, W) whose top-left pixel is the top-left of tile (x0, y0).
    """
    x0, y0, x1, y1 = tile_range(bbox, z)
    width, height = (x1 - x0 + 1) * TILE_SIZE, (y1 - y0 + 1) * TILE_SIZE
    left, top = tile_origin_3857(x0, y0, z)
    pixel = 2 * ORIGIN_SHIFT_M / (TILE_SIZE * 2**z)
    right, bottom = left + width * pixel, top - height * pixel
    dst_transform = transform_from_bounds(left, bottom, right, top, width, height)

    # One resampling step from the native grid per zoom. Average when shrinking (anti-aliases the
    # lower zooms); bilinear at ~native size (z14 is 9.3 m/px against 10 m data).
    shrinking = ground_resolution_m(z, bbox.center_lat) > 1.5 * NATIVE_RES_M
    resampling = Resampling.average if shrinking else Resampling.bilinear

    canvas = np.zeros((4, height, width), dtype=np.uint8)
    reproject(
        source=scene.rgba,
        destination=canvas,
        src_transform=scene.transform,
        src_crs=scene.crs,
        dst_transform=dst_transform,
        dst_crs="EPSG:3857",
        resampling=resampling,
    )

    # Clip to the bbox: a lat/lon rectangle is also an axis-aligned rectangle in Web Mercator.
    bx0, by0 = lonlat_to_3857(bbox.west, bbox.south)
    bx1, by1 = lonlat_to_3857(bbox.east, bbox.north)
    xs = left + (np.arange(width) + 0.5) * pixel
    ys = top - (np.arange(height) + 0.5) * pixel
    inside = np.outer((ys >= by0) & (ys <= by1), (xs >= bx0) & (xs <= bx1))
    canvas[:, ~inside] = 0
    return canvas, (x0, y0, x1, y1)


def encode_tile(tile: np.ndarray, fmt: str, quality: int) -> bytes:
    """tile is (4, 256, 256) RGBA uint8."""
    hwc = np.ascontiguousarray(np.moveaxis(tile, 0, -1))
    opaque = bool(tile[3].min() == 255)
    buffer = io.BytesIO()
    if fmt == "png":
        image = Image.fromarray(hwc[..., :3], "RGB") if opaque else Image.fromarray(hwc, "RGBA")
        image.save(buffer, format="PNG", optimize=True)
    elif fmt == "jpg":
        # No alpha in JPEG: clipped-away area renders black.
        Image.fromarray(hwc[..., :3], "RGB").save(buffer, format="JPEG", quality=quality, optimize=True)
    elif fmt == "webp":
        image = Image.fromarray(hwc[..., :3], "RGB") if opaque else Image.fromarray(hwc, "RGBA")
        image.save(buffer, format="WEBP", quality=quality, method=6)
    else:
        raise ValueError(fmt)
    return buffer.getvalue()


# ---------------------------------------------------------------------------------------------
# MBTiles
# ---------------------------------------------------------------------------------------------


def write_mbtiles(path: Path, tiles: list[tuple[int, int, int, bytes]], metadata: dict[str, str]) -> None:
    """
    Write an MBTiles 1.3 file. Built under a temporary name and renamed at the end, so an
    interrupted run never leaves a half-written archive where the phone expects a good one.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_name(path.name + ".partial")
    partial.unlink(missing_ok=True)

    con = sqlite3.connect(partial)
    try:
        con.executescript(
            """
            PRAGMA application_id = 1297105496;  -- 0x4D504258, "MPBX", per the MBTiles 1.3 spec
            CREATE TABLE metadata (name TEXT NOT NULL, value TEXT);
            CREATE UNIQUE INDEX metadata_name ON metadata (name);
            CREATE TABLE tiles (
                zoom_level INTEGER NOT NULL,
                tile_column INTEGER NOT NULL,
                tile_row INTEGER NOT NULL,
                tile_data BLOB NOT NULL
            );
            CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
            """
        )
        con.executemany(
            "INSERT INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
            # MBTiles rows are TMS: y counts up from the south. MapLibre flips them back.
            [(z, x, (2**z - 1) - y, sqlite3.Binary(data)) for z, x, y, data in tiles],
        )
        con.executemany("INSERT INTO metadata (name, value) VALUES (?, ?)", sorted(metadata.items()))
        con.commit()
        con.execute("VACUUM")
    finally:
        con.close()
    os.replace(partial, path)


# ---------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build an offline Sentinel-2 L2A true-colour basemap (.mbtiles) for a bounding box.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--bbox", nargs=4, type=float, required=True, metavar=("SOUTH", "WEST", "NORTH", "EAST"),
                        help="bounding box in lat/lon order, decimal degrees")
    parser.add_argument("--start", required=True, help="start of date range, YYYY-MM-DD")
    parser.add_argument("--end", required=True, help="end of date range (inclusive), YYYY-MM-DD")
    parser.add_argument("--max-cloud", type=float, default=20.0,
                        help="maximum scene-level cloud cover, percent (pre-filter before the bbox check)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="output .mbtiles path")
    parser.add_argument("--min-zoom", type=int, default=10)
    parser.add_argument("--max-zoom", type=int, default=14,
                        help="14 is native for 10 m data; 15 is 2x upsampled and only allowed with a warning")
    parser.add_argument("--format", choices=("png", "jpg", "webp"), default="png",
                        help="tile encoding. png is lossless with a transparent clip; jpg/webp are smaller")
    parser.add_argument("--quality", type=int, default=85, help="jpg/webp quality")
    parser.add_argument("--scene", help="use this exact STAC item id (skips selection; for reproducing a build)")
    parser.add_argument("--candidates", type=int, default=12,
                        help="how many of the lowest scene-cloud items get a per-bbox SCL cloud check")
    parser.add_argument("--low-pct", type=float, default=1.0, help="stretch black point, percentile per band")
    parser.add_argument("--high-pct", type=float, default=99.5, help="stretch white point, percentile (shared)")
    parser.add_argument("--gamma", type=float, default=1.6, help="stretch gamma; >1 brightens mid-tones")
    args = parser.parse_args(argv)

    south, west, north, east = args.bbox
    if not (-85 < south < north < 85 and -180 <= west < east <= 180):
        parser.error("--bbox must be SOUTH WEST NORTH EAST with south < north and west < east")
    if not 0 <= args.min_zoom <= args.max_zoom:
        parser.error("--min-zoom must be between 0 and --max-zoom")
    if args.max_zoom > 15:
        parser.error("--max-zoom above 15 is pure upsampling of 10 m data; MapLibre over-zooms z14 for free")
    return args


def main(argv: list[str]) -> None:
    args = parse_args(argv)
    bbox = BBox(*args.bbox)
    w_km, h_km = bbox.size_km()
    started = time.time()

    log(f"BBox  S {bbox.south}  W {bbox.west}  N {bbox.north}  E {bbox.east}")
    log(f"      {w_km:.2f} km x {h_km:.2f} km = {w_km * h_km:.2f} km^2")
    if args.max_zoom == 15:
        log("warning: z15 is 4.7 m/px from 10 m data - 2x upsampled, no extra detail, ~4x the tiles.")

    with rasterio.Env(**GDAL_ENV):
        if args.scene:
            item = Client.open(STAC_API).get_collection(COLLECTION).get_item(args.scene)
            if item is None:
                sys.exit(f"STAC item {args.scene} not found in {COLLECTION}")
            chosen = Candidate(item, float(item.properties.get("eo:cloud_cover", 100.0)))
            score_bbox_cloud(chosen, bbox)
            log(f"Pinned scene {chosen.id}: unusable over bbox {chosen.bbox_unusable * 100:.2f} %")
        else:
            log(f"Searching {COLLECTION} on {STAC_API}  {args.start} .. {args.end}, scene cloud <= {args.max_cloud} %")
            candidates = search_candidates(bbox, args.start, args.end, args.max_cloud)
            log(f"  {len(candidates)} scenes match")
            if not candidates:
                sys.exit("No scenes match. Widen the date range or raise --max-cloud.")
            chosen = select_scene(candidates, bbox, args.candidates)

        item = chosen.item
        log()
        log(f"SELECTED  {item.id}")
        log(f"  acquired        {item.datetime.isoformat()}")
        log(f"  platform        {item.properties.get('platform')}  MGRS {item.properties.get('grid:code')}")
        log(f"  scene cloud     {chosen.scene_cloud:.3f} %")
        log(f"  unusable (bbox) {chosen.bbox_unusable * 100:.2f} %")
        log()

        log("Reading B04/B03/B02 over the bbox:")
        scene = read_true_colour(item, bbox, args.low_pct, args.high_pct, args.gamma)
        log(f"  stretch: {scene.stretch_note}")

    tiles: list[tuple[int, int, int, bytes]] = []
    preview = None
    log()
    log("Tiling:")
    for z in range(args.min_zoom, args.max_zoom + 1):
        canvas, (x0, y0, x1, y1) = render_zoom(scene, bbox, z)
        count = 0
        for j, y in enumerate(range(y0, y1 + 1)):
            for i, x in enumerate(range(x0, x1 + 1)):
                tile = canvas[:, j * TILE_SIZE:(j + 1) * TILE_SIZE, i * TILE_SIZE:(i + 1) * TILE_SIZE]
                if not tile[3].any():
                    continue  # entirely outside the bbox
                tiles.append((z, x, y, encode_tile(tile, args.format, args.quality)))
                count += 1
        log(f"  z{z:<2}  {ground_resolution_m(z, bbox.center_lat):6.1f} m/px  {count:3d} tiles")
        if z == args.max_zoom:
            preview = canvas

    year = item.datetime.year
    metadata = {
        "name": "SpatialMapper Sentinel-2 basemap",
        "format": args.format,
        "type": "baselayer",
        "version": "1",
        "bounds": f"{bbox.west:.6f},{bbox.south:.6f},{bbox.east:.6f},{bbox.north:.6f}",
        "minzoom": str(args.min_zoom),
        "maxzoom": str(args.max_zoom),
        "attribution": f"Contains modified Copernicus Sentinel data {year}",
        "description": f"Sentinel-2 L2A true colour (B04/B03/B02, 10 m), scene {item.id}",
        "sentinel2_scene_id": item.id,
        "sentinel2_acquired": item.datetime.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sentinel2_collection": COLLECTION,
        "sentinel2_cloud_scene_pct": f"{chosen.scene_cloud:.3f}",
        "sentinel2_unusable_bbox_pct": f"{chosen.bbox_unusable * 100:.2f}",
        "sentinel2_stretch": scene.stretch_note,
        "generator": "tools/basemap/make_sentinel2_mbtiles.py",
    }
    output = args.output.resolve()
    write_mbtiles(output, tiles, metadata)

    preview_path = output.with_suffix(".preview.png")
    if preview is not None:
        Image.fromarray(np.ascontiguousarray(np.moveaxis(preview, 0, -1)), "RGBA").save(preview_path)

    size = output.stat().st_size
    log()
    log(f"Wrote {output}")
    log(f"  {len(tiles)} tiles, z{args.min_zoom}-z{args.max_zoom}, {args.format}")
    log(f"  size {size:,} bytes ({size / 1024 / 1024:.2f} MiB)")
    log(f"  attribution: {metadata['attribution']}")
    log(f"  preview: {preview_path}")
    log(f"  done in {time.time() - started:.0f} s")
    log()
    log("Reproduce this exact build with:")
    log(f"  --bbox {bbox.south} {bbox.west} {bbox.north} {bbox.east} --scene {item.id} "
        f"--min-zoom {args.min_zoom} --max-zoom {args.max_zoom} --format {args.format} "
        f"--low-pct {args.low_pct:g} --high-pct {args.high_pct:g} --gamma {args.gamma:g} "
        f"--start {args.start} --end {args.end}")


if __name__ == "__main__":
    main(sys.argv[1:])
