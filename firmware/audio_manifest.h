#pragma once
struct Segment { uint32_t offset, samples; };
struct Track { const char *name; Segment music, warning; };
constexpr Track TRACKS[] = {
  {"etchujima", {8, 529200}, {264608, 97571}},
  {"inage-kaigan", {313394, 242550}, {434669, 80196}},
  {"JK03-south", {474767, 396900}, {673217, 100350}},
  {"JT03_SGW", {723392, 374850}, {910817, 128464}},
};
constexpr uint32_t AUDIO_BYTES = 975049;
constexpr uint32_t AUDIO_CRC = 0x8b4c85d6;
