#!/usr/bin/env python3
"""NavMaster map style for heavy vehicles (OpenMapTiles schema, PMTiles source).

Original NavMaster design: wide, high-contrast roads with a clear hierarchy (motorway >
trunk > primary ...), road numbers always readable, little clutter, fuel and truck parking
kept, everything else toned down. Day and night variants are generated from one table.

Writes app/src/main/assets/style/style-day.json and style-night.json. The tablet replaces
{PMTILES} with the absolute path of the region file when it loads the style.
"""
import json
import os
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/style"

PALETTES = {
    "day": {
        "background": "#F2EFE9", "water": "#A9CCE3", "water_line": "#8FB8D6", "park": "#D5E8C7",
        "wood": "#C9DFB6", "farm": "#EDEBD8", "residential": "#E8E4DC", "industrial": "#E3DEE8",
        "building": "#DAD4CB", "building_line": "#C9C1B6", "boundary": "#9B8FB5",
        "motorway": "#E8664A", "motorway_case": "#A83A26",
        "trunk": "#F2A03D", "trunk_case": "#B36B18",
        "primary": "#F7D26B", "primary_case": "#B89632",
        "secondary": "#FBEBA6", "secondary_case": "#BFA85E",
        "tertiary": "#FFFFFF", "tertiary_case": "#B3AA9C",
        "minor": "#FFFFFF", "minor_case": "#C6BDB0", "track": "#B5A58A",
        "rail": "#9A9A9A", "tunnel_alpha": 0.55,
        "text": "#2B2B2B", "text_halo": "#FFFFFF", "road_text": "#303030",
        "place_text": "#1F1F1F", "water_text": "#3C6E91",
        "shield": "#FFFFFF", "shield_text": "#1B5E20", "shield_motorway": "#1B7F3B",
        "poi_fuel": "#1565C0", "poi_parking": "#2E7D32",
    },
    "night": {
        "background": "#1B1F24", "water": "#1F3A52", "water_line": "#2B4C69", "park": "#1E2B22",
        "wood": "#1C2A20", "farm": "#20242A", "residential": "#23272D", "industrial": "#262530",
        "building": "#2C3036", "building_line": "#363B42", "boundary": "#6B6285",
        "motorway": "#D0583E", "motorway_case": "#6E2618",
        "trunk": "#C98A36", "trunk_case": "#6A4515",
        "primary": "#BFA252", "primary_case": "#5E4F24",
        "secondary": "#8C8363", "secondary_case": "#4A452F",
        "tertiary": "#6B6E73", "tertiary_case": "#3A3D42",
        "minor": "#54585E", "minor_case": "#34373C", "track": "#5A5242",
        "rail": "#5E5E5E", "tunnel_alpha": 0.45,
        "text": "#E4E4E4", "text_halo": "#15181C", "road_text": "#EDEDED",
        "place_text": "#F2F2F2", "water_text": "#7FA8C9",
        "shield": "#1B1F24", "shield_text": "#8DE0A0", "shield_motorway": "#2FA65A",
        "poi_fuel": "#64B5F6", "poi_parking": "#81C784",
    },
}

# folder names without spaces: MapLibre builds asset:// paths from these names
FONT = ["NotoSansRegular"]
FONT_BOLD = ["NotoSansMedium"]
FONT_ITALIC = ["NotoSansItalic"]

# road classes: (OMT class, colour key, casing key, width stops {zoom: px})
ROADS = [
    ("motorway", "motorway", "motorway_case", {6: 1.2, 9: 2.6, 12: 5, 14: 9, 16: 16, 18: 30}),
    ("trunk", "trunk", "trunk_case", {6: 0.8, 9: 2, 12: 4, 14: 8, 16: 14, 18: 26}),
    ("primary", "primary", "primary_case", {7: 0.6, 10: 1.6, 12: 3.2, 14: 7, 16: 12, 18: 24}),
    ("secondary", "secondary", "secondary_case", {9: 0.6, 12: 2.4, 14: 5.5, 16: 10, 18: 20}),
    ("tertiary", "tertiary", "tertiary_case", {11: 0.6, 12: 1.6, 14: 4.5, 16: 9, 18: 18}),
    ("minor", "minor", "minor_case", {13: 0.8, 14: 2.8, 16: 7, 18: 15}),
    ("service", "minor", "minor_case", {14: 0.8, 16: 3.5, 18: 8}),
]


def stops(d, mult=1.0, add=0.0):
    out = ["interpolate", ["exponential", 1.5], ["zoom"]]
    for z, v in sorted(d.items()):
        out += [z, round(v * mult + add, 2)]
    return out


def road_filter(cls, brunnel=None, ramp=None):
    f = ["all", ["==", ["get", "class"], cls]]
    if brunnel == "tunnel":
        f.append(["==", ["get", "brunnel"], "tunnel"])
    elif brunnel == "bridge":
        f.append(["==", ["get", "brunnel"], "bridge"])
    else:
        f.append(["!", ["in", ["get", "brunnel"], ["literal", ["tunnel", "bridge"]]]])
    return f


def style(name, p):
    layers = [
        {"id": "background", "type": "background", "paint": {"background-color": p["background"]}},
        {"id": "landcover-wood", "type": "fill", "source": "omt", "source-layer": "landcover",
         "filter": ["==", ["get", "class"], "wood"], "paint": {"fill-color": p["wood"], "fill-opacity": 0.7}},
        {"id": "landcover-farm", "type": "fill", "source": "omt", "source-layer": "landcover",
         "filter": ["in", ["get", "class"], ["literal", ["farmland", "grass"]]],
         "paint": {"fill-color": p["farm"], "fill-opacity": 0.6}},
        {"id": "landuse-residential", "type": "fill", "source": "omt", "source-layer": "landuse",
         "filter": ["in", ["get", "class"], ["literal", ["residential", "suburb", "neighbourhood"]]],
         "paint": {"fill-color": p["residential"], "fill-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0.4, 14, 0.9]}},
        {"id": "landuse-industrial", "type": "fill", "source": "omt", "source-layer": "landuse",
         "filter": ["in", ["get", "class"], ["literal", ["industrial", "commercial", "retail", "railway"]]],
         "paint": {"fill-color": p["industrial"], "fill-opacity": 0.8}},
        {"id": "park", "type": "fill", "source": "omt", "source-layer": "park",
         "paint": {"fill-color": p["park"], "fill-opacity": 0.7}},
        {"id": "water", "type": "fill", "source": "omt", "source-layer": "water",
         "paint": {"fill-color": p["water"]}},
        {"id": "waterway", "type": "line", "source": "omt", "source-layer": "waterway", "minzoom": 9,
         "paint": {"line-color": p["water_line"],
                   "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.5, 14, 1.5, 18, 4]}},
        {"id": "building", "type": "fill", "source": "omt", "source-layer": "building", "minzoom": 14,
         "paint": {"fill-color": p["building"], "fill-outline-color": p["building_line"],
                   "fill-opacity": ["interpolate", ["linear"], ["zoom"], 14, 0.3, 16, 0.9]}},
        {"id": "boundary-country", "type": "line", "source": "omt", "source-layer": "boundary",
         "filter": ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
         "paint": {"line-color": p["boundary"], "line-width": ["interpolate", ["linear"], ["zoom"], 3, 1, 10, 2.5],
                   "line-dasharray": [4, 2]}},
        {"id": "boundary-region", "type": "line", "source": "omt", "source-layer": "boundary",
         "filter": ["all", ["==", ["get", "admin_level"], 4], ["!=", ["get", "maritime"], 1]], "minzoom": 6,
         "paint": {"line-color": p["boundary"], "line-width": 1, "line-opacity": 0.5, "line-dasharray": [3, 2]}},
    ]
    # tunnels (dim), normal roads, bridges on top: casing first, then fill
    for brunnel in ("tunnel", None, "bridge"):
        tag = brunnel or "road"
        for cls, col, case, w in reversed(ROADS):
            flt = road_filter(cls, brunnel)
            base = {"source": "omt", "source-layer": "transportation", "filter": flt,
                    "layout": {"line-cap": "butt" if brunnel == "tunnel" else "round",
                               "line-join": "round"}}
            layers.append(dict(base, id=f"{tag}-{cls}-case", type="line",
                               paint={"line-color": p[case], "line-width": stops(w, 1.0, 2.0),
                                      "line-opacity": p["tunnel_alpha"] if brunnel == "tunnel" else 1}))
        for cls, col, case, w in reversed(ROADS):
            flt = road_filter(cls, brunnel)
            base = {"source": "omt", "source-layer": "transportation", "filter": flt,
                    "layout": {"line-cap": "round", "line-join": "round"}}
            layers.append(dict(base, id=f"{tag}-{cls}", type="line",
                               paint={"line-color": p[col], "line-width": stops(w),
                                      "line-opacity": p["tunnel_alpha"] if brunnel == "tunnel" else 1}))
    layers += [
        {"id": "road-track", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 13,
         "filter": ["==", ["get", "class"], "track"],
         "paint": {"line-color": p["track"], "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.8, 18, 3],
                   "line-dasharray": [2, 1.5]}},
        {"id": "rail", "type": "line", "source": "omt", "source-layer": "transportation", "minzoom": 10,
         "filter": ["==", ["get", "class"], "rail"],
         "paint": {"line-color": p["rail"], "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 16, 2],
                   "line-dasharray": [3, 3]}},
        {"id": "water-name", "type": "symbol", "source": "omt", "source-layer": "water_name", "minzoom": 8,
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_ITALIC,
                    "text-size": 13},
         "paint": {"text-color": p["water_text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.2}},
        # street names along the road
        {"id": "road-name", "type": "symbol", "source": "omt", "source-layer": "transportation_name", "minzoom": 13,
         "filter": ["!", ["in", ["get", "class"], ["literal", ["motorway", "trunk"]]]],
         "layout": {"symbol-placement": "line", "text-field": ["coalesce", ["get", "name:it"], ["get", "name"]],
                    "text-font": FONT_BOLD,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 13, 12, 16, 15, 18, 18],
                    "text-max-angle": 30, "symbol-spacing": 350},
         "paint": {"text-color": p["road_text"], "text-halo-color": p["text_halo"], "text-halo-width": 2}},
        # road numbers as shields: A14, SS16 ... always readable for a driver
        {"id": "road-shield-motorway", "type": "symbol", "source": "omt", "source-layer": "transportation_name",
         "minzoom": 7, "filter": ["all", ["has", "ref"], ["in", ["get", "class"], ["literal", ["motorway"]]]],
         "layout": {"symbol-placement": "line", "symbol-spacing": 500, "text-field": ["get", "ref"],
                    "text-font": FONT_BOLD, "text-size": 13, "text-rotation-alignment": "viewport",
                    "text-padding": 4},
         "paint": {"text-color": "#FFFFFF", "text-halo-color": p["shield_motorway"], "text-halo-width": 4}},
        {"id": "road-shield", "type": "symbol", "source": "omt", "source-layer": "transportation_name",
         "minzoom": 9, "filter": ["all", ["has", "ref"],
                                  ["in", ["get", "class"], ["literal", ["trunk", "primary", "secondary"]]]],
         "layout": {"symbol-placement": "line", "symbol-spacing": 450, "text-field": ["get", "ref"],
                    "text-font": FONT_BOLD, "text-size": 12, "text-rotation-alignment": "viewport",
                    "text-padding": 4},
         "paint": {"text-color": p["shield_text"], "text-halo-color": p["shield"], "text-halo-width": 4}},
        {"id": "poi-fuel", "type": "circle", "source": "omt", "source-layer": "poi", "minzoom": 13,
         "filter": ["==", ["get", "class"], "fuel"],
         "paint": {"circle-color": p["poi_fuel"], "circle-radius": ["interpolate", ["linear"], ["zoom"], 13, 3, 17, 7],
                   "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 1.5}},
        {"id": "poi-parking", "type": "circle", "source": "omt", "source-layer": "poi", "minzoom": 16,
         "filter": ["all", ["==", ["get", "class"], "parking"], ["has", "name"]],
         "paint": {"circle-color": p["poi_parking"], "circle-radius": ["interpolate", ["linear"], ["zoom"], 14, 3, 17, 6],
                   "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 1.5}},
        {"id": "poi-label", "type": "symbol", "source": "omt", "source-layer": "poi", "minzoom": 15,
         "filter": ["in", ["get", "class"], ["literal", ["fuel", "parking"]]],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT,
                    "text-size": 12, "text-offset": [0, 1.1], "text-anchor": "top", "text-optional": True},
         "paint": {"text-color": p["text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.5}},
        {"id": "housenumber", "type": "symbol", "source": "omt", "source-layer": "housenumber", "minzoom": 17,
         "layout": {"text-field": ["get", "housenumber"], "text-font": FONT, "text-size": 11},
         "paint": {"text-color": p["text"], "text-halo-color": p["text_halo"], "text-halo-width": 1, "text-opacity": 0.75}},
        {"id": "place-village", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 11,
         "filter": ["in", ["get", "class"], ["literal", ["village", "suburb", "hamlet", "neighbourhood"]]],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 11, 11, 15, 15]},
         "paint": {"text-color": p["place_text"], "text-halo-color": p["text_halo"], "text-halo-width": 1.8}},
        {"id": "place-town", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 8,
         "filter": ["==", ["get", "class"], "town"],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_BOLD,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 8, 11, 13, 17]},
         "paint": {"text-color": p["place_text"], "text-halo-color": p["text_halo"], "text-halo-width": 2}},
        {"id": "place-city", "type": "symbol", "source": "omt", "source-layer": "place", "minzoom": 4,
         "filter": ["==", ["get", "class"], "city"],
         "layout": {"text-field": ["coalesce", ["get", "name:it"], ["get", "name"]], "text-font": FONT_BOLD,
                    "text-size": ["interpolate", ["linear"], ["zoom"], 4, 11, 10, 20]},
         "paint": {"text-color": p["place_text"], "text-halo-color": p["text_halo"], "text-halo-width": 2.2}},
    ]
    return {
        "version": 8,
        "name": f"NavMaster Truck {name}",
        "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
        "sources": {
            "omt": {"type": "vector", "url": "pmtiles://file://{PMTILES}",
                    "attribution": "© OpenStreetMap contributors · © OpenMapTiles"},
        },
        "layers": layers,
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, pal in PALETTES.items():
        with open(os.path.join(OUT, f"style-{name}.json"), "w", encoding="utf-8") as f:
            json.dump(style(name, pal), f, ensure_ascii=False, separators=(",", ":"))
    print("styles written to", OUT)


if __name__ == "__main__":
    main()
