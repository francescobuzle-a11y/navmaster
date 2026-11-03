#!/usr/bin/env python3
"""NavMaster map style for heavy vehicles (OpenMapTiles schema, PMTiles source).

Design for driving a lorry, day and night from one table:

- roads drawn at their real width in metres from zoom 16 (one motorway carriageway about 11 m,
  a main road 9 m, a local street 5.5 m), so that in the tilted driving view they have the right
  size against the buildings and each other; below that, widths chosen to be read at a glance;
- a clear hierarchy (motorway > trunk > primary > secondary > local), ramps narrower than the
  carriageways, tunnels faded with a dashed edge, bridges with a darker edge;
- buildings in 3D from their OpenStreetMap heights when close, flat further away;
- road numbers on real shields (motorways green, national roads blue, the others white),
  street names along the roads, one-way arrows on the streets, places ranked by size;
- few, calm colours for the land, so that the roads and the route stand out.

Writes app/src/main/assets/style/style-day.json, style-night.json and the sprite of the shields
(app/src/main/assets/sprite/nm.json/.png and nm@2x.*). The tablet replaces {PMTILES} with the
path of each country's map when it loads the style.
"""
import json
import math
import os
import struct
import sys
import zlib

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/style"
SPRITE_OUT = os.path.join(os.path.dirname(os.path.abspath(OUT)), "sprite")

PALETTES = {
    "day": {
        "background": "#F3F1ED", "residential": "#ECE8E1", "industrial": "#E9E5EE", "retail": "#F2E6E1",
        "wood": "#CDE2BF", "grass": "#E3EDD3", "farm": "#EEF0DF", "park": "#C8E3B6", "pitch": "#BFE0AE",
        "water": "#9CC8EC", "water_line": "#86B8E2", "water_text": "#2F6596",
        "building": "#E0DAD1", "building_line": "#CEC6BB", "building_3d": "#E8E3DB", "building_3d_top": "#F5F2ED",
        "boundary": "#9C8FB8", "rail": "#A3A3A3", "aeroway": "#DCDCE2",
        "motorway": "#F08A4E", "motorway_case": "#B85A26",
        "trunk": "#F5B357", "trunk_case": "#BB8236",
        "primary": "#FFD873", "primary_case": "#C29E43",
        "secondary": "#FFF1B8", "secondary_case": "#C4B482",
        "tertiary": "#FFFFFF", "tertiary_case": "#C9C1B4",
        "minor": "#FFFFFF", "minor_case": "#D3CCC1", "service": "#FFFFFF", "service_case": "#DAD4CB",
        "track": "#B09F84", "tunnel_fill": "#F7F5F2", "bridge_case": "#8C8478",
        "oneway": "#8E877C",
        "text": "#2A2A2A", "text_halo": "#FFFFFF", "road_text": "#363636", "place_text": "#1D1D1D", "suburb_text": "#555555",
        "poi_fuel": "#E65100", "poi_parking": "#1565C0",
    },
    "night": {
        "background": "#161A20", "residential": "#1B2027", "industrial": "#1E1D27", "retail": "#221D22",
        "wood": "#16241B", "grass": "#18221B", "farm": "#191E1E", "park": "#172A1D", "pitch": "#183020",
        "water": "#0E2B47", "water_line": "#16395B", "water_text": "#7FAAD3",
        "building": "#242930", "building_line": "#2E343C", "building_3d": "#2A3038", "building_3d_top": "#323A44",
        "boundary": "#6E6590", "rail": "#5A5E66", "aeroway": "#22252C",
        "motorway": "#D67A43", "motorway_case": "#5C2C13",
        "trunk": "#C89547", "trunk_case": "#4F3816",
        "primary": "#B49E58", "primary_case": "#45391B",
        "secondary": "#8A8467", "secondary_case": "#3A3727",
        "tertiary": "#59606A", "tertiary_case": "#2A2F36",
        "minor": "#4A5059", "minor_case": "#262A30", "service": "#40464E", "service_case": "#23272D",
        "track": "#5C5342", "tunnel_fill": "#2A2F36", "bridge_case": "#0B0D10",
        "oneway": "#7D848D",
        "text": "#E6E6E6", "text_halo": "#101317", "road_text": "#EDEDED", "place_text": "#F4F4F4", "suburb_text": "#B4B8BE",
        "poi_fuel": "#FF8A3D", "poi_parking": "#64A8F0",
    },
}

# folder names without spaces: MapLibre builds asset:// paths from these names
FONT = ["NotoSansRegular"]
FONT_BOLD = ["NotoSansMedium"]
FONT_ITALIC = ["NotoSansItalic"]

# metres per pixel at zoom z (512 px tiles) at 45° of latitude, Italy's middle
MPP45 = 40075016.686 * math.cos(math.radians(45)) / 512


def metric(metres, z):
    return metres / (MPP45 / 2 ** z)


# road classes: (filter, colour, casing, width stops below 16 {zoom: px}, real width in metres, minzoom)
ROADS = [
    ("motorway", ["==", ["get", "class"], "motorway"], "motorway", "motorway_case", {5: 0.8, 8: 1.6, 10: 2.6, 12: 4.0, 14: 6.0}, 11.0, 5),
    ("trunk", ["==", ["get", "class"], "trunk"], "trunk", "trunk_case", {6: 0.7, 9: 1.6, 12: 3.4, 14: 5.4}, 9.5, 6),
    ("primary", ["==", ["get", "class"], "primary"], "primary", "primary_case", {7: 0.6, 10: 1.4, 12: 2.8, 14: 4.8}, 9.0, 7),
    ("secondary", ["==", ["get", "class"], "secondary"], "secondary", "secondary_case", {9: 0.6, 12: 2.0, 14: 4.0}, 7.5, 9),
    ("tertiary", ["==", ["get", "class"], "tertiary"], "tertiary", "tertiary_case", {11: 0.6, 13: 1.8, 14: 3.2}, 6.5, 11),
    ("minor", ["==", ["get", "class"], "minor"], "minor", "minor_case", {12: 0.4, 14: 2.2}, 5.5, 12),
    ("service", ["==", ["get", "class"], "service"], "service", "service_case", {14: 0.6, 15: 1.2}, 3.5, 14),
]


def width(stops_low, metres, mult=1.0, add=0.0):
    """Width in px: the stops below zoom 16, then the real width (doubling at each zoom)."""
    out = ["interpolate", ["exponential", 2], ["zoom"]]
    pts = dict(stops_low)
    for z in (16, 18, 20):
        pts[z] = metric(metres, z)
    for z, v in sorted(pts.items()):
        out += [z, round(v * mult + add, 2)]
    return out


def casing(stops_low, metres):
    # an edge of about 0.6 m each side when close, a fine line further away
    out = ["interpolate", ["exponential", 2], ["zoom"]]
    pts = {z: v + 1.6 for z, v in stops_low.items()}
    for z in (16, 18, 20):
        pts[z] = metric(metres + 1.2, z)
    for z, v in sorted(pts.items()):
        out += [z, round(v, 2)]
    return out


def road_filter(cls_filter, brunnel=None, ramp=None):
    f = ["all", cls_filter]
    if brunnel in ("tunnel", "bridge"):
        f.append(["==", ["get", "brunnel"], brunnel])
    else:
        f.append(["!", ["in", ["get", "brunnel"], ["literal", ["tunnel", "bridge"]]]])
    if ramp is True:
        f.append(["==", ["get", "ramp"], 1])
    elif ramp is False:
        f.append(["!=", ["get", "ramp"], 1])
    return f


def road_layers(p):
    out = []
    for brunnel in ("tunnel", None, "bridge"):
        tag = brunnel or "road"
        # ramps narrower than the carriageways (about 6 m)
        variants = []
        for name, flt, col, case, low, metres, minz in ROADS:
            if name in ("motorway", "trunk", "primary"):
                variants.append((name, road_filter(flt, brunnel, False), col, case, low, metres, minz))
                variants.append((name + "-ramp", road_filter(flt, brunnel, True), col, case,
                                 {z: v * 0.7 for z, v in low.items()}, 6.0, max(minz, 10)))
            else:
                variants.append((name, road_filter(flt, brunnel), col, case, low, metres, minz))
        # casings first (lowest class first), then fills
        for name, flt, col, case, low, metres, minz in reversed(variants):
            paint = {"line-color": p["bridge_case"] if brunnel == "bridge" else p[case],
                     "line-width": casing(low, metres + (0.8 if brunnel == "bridge" else 0.0))}
            if brunnel == "tunnel":
                paint["line-dasharray"] = [1.2, 0.8]
                paint["line-opacity"] = 0.7
            out.append({"id": f"{tag}-{name}-case", "type": "line", "source": "omt", "source-layer": "transportation",
                        "minzoom": max(minz, 10 if name in ("minor", "service", "tertiary") else minz), "filter": flt,
                        "layout": {"line-cap": "butt" if brunnel else "round", "line-join": "round"}, "paint": paint})
        for name, flt, col, case, low, metres, minz in reversed(variants):
            paint = {"line-color": p["tunnel_fill"] if brunnel == "tunnel" else p[col], "line-width": width(low, metres)}
            if brunnel == "tunnel":
                paint["line-opacity"] = 0.9
            out.append({"id": f"{tag}-{name}", "type": "line", "source": "omt", "source-layer": "transportation",
                        "minzoom": minz, "filter": flt,
                        "layout": {"line-cap": "butt" if brunnel else "round", "line-join": "round"}, "paint": paint})
        if brunnel is None:
            # tracks: a dashed line, under the bridges
            out.append({"id": "road-track", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 13,
                        "filter": ["all", ["==", ["get", "class"], "track"], ["!", ["in", ["get", "brunnel"], ["literal", ["tunnel", "bridge"]]]]],
                        "paint": {"line-color": p["track"], "line-width": ["interpolate", ["exponential", 2], ["zoom"], 13, 0.8, 16, 2.0, 18, metric(3.0, 18), 20, metric(3.0, 20)],
                                  "line-dasharray": [2.5, 1.5]}})
            out.append({"id": "rail", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 10,
                        "filter": ["in", ["get", "class"], ["literal", ["rail", "transit"]]],
                        "paint": {"line-color": p["rail"], "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 16, 2.2, 19, 4],
                                  "line-dasharray": [3, 2.5]}})
    return out


def road_names(p):
    def layer(lid, classes, minz):
        return {"id": lid, "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": minz,
                "filter": ["all", ["has", "name"], ["in", ["get", "class"], ["literal", classes]]],
                "layout": {"symbol-placement": "line", "text-field": ["coalesce", ["get", "name:it"], ["get", "name"]],
                           "text-font": FONT_BOLD, "text-letter-spacing": 0.02,
                           "text-size": ["interpolate", ["linear"], ["zoom"], 13, 11.5, 16, 14.5, 18, 18],
                           "text-max-angle": 28, "symbol-spacing": 320, "text-padding": 6,
                           "symbol-sort-key": ["match", ["get", "class"], "trunk", 1, "primary", 2, "secondary", 3, "tertiary", 4, 5]},
                "paint": {"text-color": p["road_text"], "text-halo-color": p["text_halo"], "text-halo-width": 2.2}}
    return [layer("road-name-major", ["trunk", "primary", "secondary", "tertiary"], 13),
            layer("road-name-minor", ["minor", "service"], 15)]


def road_shields(p):
    """Road numbers on shields: motorways green, national roads blue (also the SS/SR mapped as
    secondary roads, as on the Italian plates), the others white."""
    national_ref = ["in", ["slice", ["get", "ref"], 0, 2], ["literal", ["SS", "SR", "SN", "RN"]]]

    def layer(lid, where, minz, image, text_color, sort):
        return {"id": lid, "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": minz,
                "filter": ["all", ["has", "ref"], ["<=", ["coalesce", ["get", "ref_length"], 99], 7], where],
                "layout": {"symbol-placement": "line", "symbol-spacing": ["interpolate", ["linear"], ["zoom"], 8, 300, 14, 450],
                           "text-field": ["get", "ref"], "text-font": FONT_BOLD,
                           "text-size": ["interpolate", ["linear"], ["zoom"], 8, 11, 14, 13, 17, 15],
                           "text-rotation-alignment": "viewport", "icon-rotation-alignment": "viewport",
                           "icon-image": image, "icon-text-fit": "both", "icon-text-fit-padding": [2, 5, 2, 5],
                           "symbol-sort-key": sort, "text-padding": 6, "icon-padding": 6},
                "paint": {"text-color": text_color}}
    minor = ["in", ["get", "class"], ["literal", ["secondary", "tertiary"]]]
    return [layer("road-shield-secondary", ["all", ["==", ["get", "class"], "secondary"], ["!", national_ref]], 11, "shield-white", "#1C1C1C", 3),
            layer("road-shield-national", ["any", ["in", ["get", "class"], ["literal", ["trunk", "primary"]]], ["all", minor, national_ref]],
                  9, "shield-blue", "#FFFFFF", 1),
            layer("road-shield-motorway", ["==", ["get", "class"], "motorway"], 7, "shield-green", "#FFFFFF", 0)]


def style(name, p):
    layers = [
        {"id": "background", "type": "background", "paint": {"background-color": p["background"]}},
        {"id": "landuse-residential", "type": "fill", "source": "omt", "source-layer": "landuse",
         "filter": ["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood"]]],
         "paint": {"fill-color": p["residential"], "fill-opacity": ["interpolate", ["linear"], ["zoom"], 8, 0.5, 14, 1.0, 17, 0.7]}},
        {"id": "landuse-industrial", "type": "fill", "source": "omt", "source-layer": "landuse",
         "filter": ["in", ["get", "class"], ["literal", ["industrial", "commercial", "railway"]]],
         "paint": {"fill-color": p["industrial"], "fill-opacity": 0.9}},
        {"id": "landuse-retail", "type": "fill", "source": "omt", "source-layer": "landuse",
         "filter": ["==", ["get", "class"], "retail"], "paint": {"fill-color": p["retail"], "fill-opacity": 0.9}},
        {"id": "landcover-farm", "type": "fill", "source": "omt", "source-layer": "landcover",
         "filter": ["==", ["get", "class"], "farmland"], "paint": {"fill-color": p["farm"], "fill-opacity": 0.7}},
        {"id": "landcover-grass", "type": "fill", "source": "omt", "source-layer": "landcover",
         "filter": ["==", ["get", "class"], "grass"], "paint": {"fill-color": p["grass"], "fill-opacity": 0.8}},
        {"id": "landcover-wood", "type": "fill", "source": "omt", "source-layer": "landcover",
         "filter": ["==", ["get", "class"], "wood"], "paint": {"fill-color": p["wood"], "fill-opacity": 0.85}},
        {"id": "park", "type": "fill", "source": "omt", "source-layer": "park",
         "paint": {"fill-color": p["park"], "fill-opacity": 0.8}},
        {"id": "aeroway", "type": "fill", "source": "omt", "source-layer": "aeroway", "minzoom": 11,
         "filter": ["==", ["geometry-type"], "Polygon"], "paint": {"fill-color": p["aeroway"]}},
        {"id": "water", "type": "fill", "source": "omt", "source-layer": "water",
         "paint": {"fill-color": p["water"], "fill-antialias": True}},
        {"id": "waterway", "type": "line", "source": "omt", "source-layer": "waterway", "minzoom": 9,
         "paint": {"line-color": p["water_line"],
                   "line-width": ["interpolate", ["exponential", 1.6], ["zoom"], 9, 0.5, 14, 1.6, 18, 6]}},
        # buildings: flat further away, in 3D (OpenStreetMap heights) when close
        {"id": "building", "type": "fill", "source": "omt", "source-layer": "building", "minzoom": 13, "maxzoom": 16,
         "paint": {"fill-color": p["building"], "fill-outline-color": p["building_line"],
                   "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0.0, 14, 0.8]}},
        {"id": "boundary-country", "type": "line", "source": "omt", "source-layer": "boundary",
         "filter": ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
         "paint": {"line-color": p["boundary"], "line-width": ["interpolate", ["linear"], ["zoom"], 3, 1, 10, 2.5, 16, 4],
                   "line-dasharray": [4, 2]}},
        {"id": "boundary-region", "type": "line", "source": "omt", "source-layer": "boundary", "minzoom": 6,
         "filter": ["all", ["==", ["get", "admin_level"], 4], ["!=", ["get", "maritime"], 1]],
         "paint": {"line-color": p["boundary"], "line-width": 1, "line-opacity": 0.5, "line-dasharray": [3, 2]}},
    ]
    layers += road_layers(p)
    layers += [
        {"id": "building-3d", "type": "fill-extrusion", "source": "omt", "source-layer": "building", "minzoom": 15,
         "paint": {"fill-extrusion-color": ["interpolate", ["linear"], ["coalesce", ["get", "render_height"], 6], 0, p["building_3d"], 40, p["building_3d_top"]],
                   "fill-extrusion-height": ["interpolate", ["linear"], ["zoom"], 15, 0, 16, ["coalesce", ["get", "render_height"], 6]],
                   "fill-extrusion-base": ["coalesce", ["get", "render_min_height"], 0],
                   "fill-extrusion-opacity": ["interpolate", ["linear"], ["zoom"], 15, 0.0, 16, 0.72, 18, 0.8], "fill-extrusion-vertical-gradient": True}},
        # one-way streets: small arrows in the direction of travel
        {"id": "oneway", "type": "symbol", "source": "omt", "source-layer": "transportation", "minzoom": 16,
         "filter": ["all", ["in", ["get", "oneway"], ["literal", [1, -1]]],
                    ["in", ["get", "class"], ["literal", ["primary", "secondary", "tertiary", "minor", "service"]]]],
         "layout": {"symbol-placement": "line", "symbol-spacing": 140, "text-field": ["case", ["==", ["get", "oneway"], -1], "←", "→"],
                    "text-font": FONT_BOLD, "text-size": ["interpolate", ["linear"], ["zoom"], 16, 11, 19, 16],
                    "text-keep-upright": False, "text-allow-overlap": False, "text-padding": 2},
         "paint": {"text-color": p["oneway"], "text-opacity": 0.75}},
        {"id": "water-name", "type": "symbol", "source": "omt", "source-layer": "water_name", "minzoom": 8,
         "filter": ["==", ["geometry-type"], "Point"],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_ITALIC,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 8, 11, 14, 14]},
         "paint": {"text-color": p["water_text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.2}},
        {"id": "water-name-line", "type": "symbol", "source": "omt", "source-layer": "water_name", "minzoom": 8,
         "filter": ["==", ["geometry-type"], "LineString"],
         "layout": {"symbol-placement": "line", "text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_ITALIC,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 8, 11, 14, 14]},
         "paint": {"text-color": p["water_text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.2}},
        {"id": "waterway-name", "type": "symbol", "source": "omt", "source-layer": "waterway", "minzoom": 13,
         "filter": ["has", "name"],
         "layout": {"symbol-placement": "line", "text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_ITALIC,
                    "text-size": 12, "symbol-spacing": 500},
         "paint": {"text-color": p["water_text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.2}},
        # street names along the road, the bigger roads first
    ] + road_names(p) + road_shields(p) + [
        {"id": "poi-fuel", "type": "circle", "source": "omt", "source-layer": "poi", "minzoom": 13,
         "filter": ["==", ["get", "class"], "fuel"],
         "paint": {"circle-color": p["poi_fuel"], "circle-radius": ["interpolate", ["linear"], ["zoom"], 13, 3, 17, 7],
                   "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 1.5}},
        # the parking sign: a small fixed-size picture (blue square, white P), only close up
        {"id": "poi-parking", "type": "symbol", "source": "omt", "source-layer": "poi", "minzoom": 16,
         "filter": ["==", ["get", "class"], "parking"],
         "layout": {"icon-image": "poi-parking", "icon-size": ["interpolate", ["linear"], ["zoom"], 16, 0.75, 18, 1.0],
                    "icon-allow-overlap": False, "icon-padding": 4}},
        {"id": "poi-label", "type": "symbol", "source": "omt", "source-layer": "poi", "minzoom": 16,
         "filter": ["all", ["in", ["get", "class"], ["literal", ["fuel", "parking"]]], ["has", "name"]],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT,
                    "text-size": 12, "text-offset": [0, 1.2], "text-anchor": "top", "text-optional": True, "text-max-width": 9},
         "paint": {"text-color": p["text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.5}},
        {"id": "housenumber", "type": "symbol", "source": "omt", "source-layer": "housenumber", "minzoom": 17.5,
         "layout": {"text-field": ["get", "housenumber"], "text-font": FONT, "text-size": 11},
         "paint": {"text-color": p["text"], "text-halo-color": p["text_halo"], "text-halo-width": 1, "text-opacity": 0.7}},
        {"id": "place-suburb", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 12, "maxzoom": 17,
         "filter": ["in", ["get", "class"], ["literal", ["suburb", "neighbourhood", "quarter"]]],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT, "text-transform": "uppercase",
                    "text-letter-spacing": 0.08, "text-size": ["interpolate", ["linear"], ["zoom"], 12, 10, 16, 13], "text-max-width": 8},
         "paint": {"text-color": p["suburb_text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.6}},
        {"id": "place-village", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 10,
         "filter": ["in", ["get", "class"], ["literal", ["village", "hamlet", "isolated_dwelling"]]],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_BOLD,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 10, 11, 15, 16], "text-max-width": 8,
                    "symbol-sort-key": ["-", 100, ["coalesce", ["get", "rank"], 50]]},
         "paint": {"text-color": p["place_text"], "text-halo-color": p["text_halo"], "text-halo-width": 2}},
        {"id": "place-town", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 7,
         "filter": ["==", ["get", "class"], "town"],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_BOLD,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 7, 11, 12, 16, 15, 19], "text-max-width": 8},
         "paint": {"text-color": p["place_text"], "text-halo-color": p["text_halo"], "text-halo-width": 2.2}},
        {"id": "place-city", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 4,
         "filter": ["==", ["get", "class"], "city"],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_BOLD,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 4, 11, 8, 15, 12, 21], "text-max-width": 8,
                    "symbol-sort-key": ["coalesce", ["get", "rank"], 10]},
         "paint": {"text-color": p["place_text"], "text-halo-color": p["text_halo"], "text-halo-width": 2.4}},
    ]
    return {
        "version": 8,
        "name": f"NavMaster Truck {name}",
        "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
        "sprite": "asset://sprite/nm",
        "sources": {
            "omt": {"type": "vector", "url": "pmtiles://file://{PMTILES}",
                    "attribution": "© OpenStreetMap contributors · © OpenMapTiles"},
        },
        "layers": layers,
    }


# ---------------------------------------------------------------------------- sprite (shields)

def png(w, h, rgba):
    raw = b"".join(b"\x00" + bytes(rgba[y * w * 4:(y + 1) * w * 4]) for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def hexrgb(c):
    c = c.lstrip("#")
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


def rounded_rect(size, radius, fill, border, border_w, scale):
    """An anti-aliased rounded rectangle (4×4 samples per pixel) with a border, RGBA bytes."""
    w = h = size * scale
    r = radius * scale
    bw = border_w * scale
    fr, fg, fb = hexrgb(fill)
    br, bg, bb = hexrgb(border)
    buf = bytearray(w * h * 4)

    def inside(x, y, inset):
        x0, y0, x1, y1 = inset, inset, w - inset, h - inset
        rr = max(r - inset, 0.0)
        if x < x0 or x > x1 or y < y0 or y > y1:
            return False
        cx = min(max(x, x0 + rr), x1 - rr)
        cy = min(max(y, y0 + rr), y1 - rr)
        return (x - cx) ** 2 + (y - cy) ** 2 <= rr * rr
    for y in range(h):
        for x in range(w):
            outer = inner = 0
            for sy in range(4):
                for sx in range(4):
                    px, py = x + (sx + 0.5) / 4, y + (sy + 0.5) / 4
                    if inside(px, py, 0.5 * scale):
                        outer += 1
                        if inside(px, py, 0.5 * scale + bw):
                            inner += 1
            if outer == 0:
                continue
            a = outer / 16
            t = inner / outer
            i = (y * w + x) * 4
            buf[i] = round(fr * t + br * (1 - t))
            buf[i + 1] = round(fg * t + bg * (1 - t))
            buf[i + 2] = round(fb * t + bb * (1 - t))
            buf[i + 3] = round(255 * a)
    return buf, w, h


SPRITES = {
    # name: (fill, border, sdf-like tinting off)
    "shield-green": ("#1E7B3F", "#FFFFFF"),
    "shield-blue": ("#1F4F9C", "#FFFFFF"),
    "shield-white": ("#FFFFFF", "#2A2A2A"),
}


def parking_sign(scale):
    """The parking sign, 16×16: a blue rounded square with a white border and a white P."""
    size = 16
    w = h = size * scale
    blue = hexrgb("#1F5FBF")
    white = (255, 255, 255)
    buf = bytearray(w * h * 4)

    def rr(x, y, x0, y0, x1, y1, r):
        if x < x0 or x > x1 or y < y0 or y > y1:
            return False
        cx = min(max(x, x0 + r), x1 - r)
        cy = min(max(y, y0 + r), y1 - r)
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r

    def p_letter(x, y):
        if 5.0 <= x <= 7.3 and 3.3 <= y <= 12.7:
            return True                      # the stem
        if 5.0 <= x <= 8.6 and (3.3 <= y <= 5.2 or 7.6 <= y <= 9.5):
            return True                      # the bars of the bowl
        if x >= 8.6:
            d = ((x - 8.6) ** 2 + (y - 6.4) ** 2) ** 0.5
            return 1.2 <= d <= 3.1           # the round of the bowl
        return False

    for py in range(h):
        for px in range(w):
            acc = [0, 0, 0, 0]
            for sy in range(4):
                for sx in range(4):
                    x = (px + (sx + 0.5) / 4) / scale
                    y = (py + (sy + 0.5) / 4) / scale
                    if not rr(x, y, 0.5, 0.5, size - 0.5, size - 0.5, 3.0):
                        continue
                    inner = rr(x, y, 1.7, 1.7, size - 1.7, size - 1.7, 2.0)
                    c = white if (not inner or p_letter(x, y)) else blue
                    acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2]; acc[3] += 1
            if acc[3] == 0:
                continue
            i = (py * w + px) * 4
            buf[i] = round(acc[0] / acc[3]); buf[i + 1] = round(acc[1] / acc[3]); buf[i + 2] = round(acc[2] / acc[3])
            buf[i + 3] = round(255 * acc[3] / 16)
    return buf, w, h


def write_sprite(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    size, radius, border = 22, 5, 1.6
    for scale, suffix in ((1, ""), (2, "@2x")):
        images = []
        for name, (fill, edge) in SPRITES.items():
            buf, w, h = rounded_rect(size, radius, fill, edge, border, scale)
            r = radius * scale
            # the shields stretch around their text (icon-text-fit)
            meta = {"stretchX": [[r + scale, w - r - scale]], "stretchY": [[r + scale, h - r - scale]], "content": [r, r, w - r, h - r]}
            images.append((name, buf, w, h, meta))
        buf, w, h = parking_sign(scale)
        images.append(("poi-parking", buf, w, h, {}))
        total_w = sum(im[2] for im in images)
        total_h = max(im[3] for im in images)
        atlas = bytearray(total_w * total_h * 4)
        index = {}
        x0 = 0
        for name, buf, w, h, meta in images:
            for y in range(h):
                atlas[(y * total_w + x0) * 4:(y * total_w + x0 + w) * 4] = buf[y * w * 4:(y + 1) * w * 4]
            index[name] = {"x": x0, "y": 0, "width": w, "height": h, "pixelRatio": scale, **meta}
            x0 += w
        with open(os.path.join(out_dir, f"nm{suffix}.png"), "wb") as f:
            f.write(png(total_w, total_h, atlas))
        with open(os.path.join(out_dir, f"nm{suffix}.json"), "w") as f:
            json.dump(index, f, separators=(",", ":"))


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, pal in PALETTES.items():
        with open(os.path.join(OUT, f"style-{name}.json"), "w", encoding="utf-8") as f:
            json.dump(style(name, pal), f, ensure_ascii=False, separators=(",", ":"))
    write_sprite(SPRITE_OUT)
    print("styles written to", OUT, "and the sprite to", SPRITE_OUT)


if __name__ == "__main__":
    main()
