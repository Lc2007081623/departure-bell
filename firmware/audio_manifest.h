#pragma once
struct Segment { uint32_t offset, samples; };
struct Track { const char *name; Segment music, warning; };
constexpr Track TRACKS[] = {
  {"etchujima", {8, 529200}, {264608, 97571}},
  {"inage-kaigan", {313394, 242550}, {434669, 80196}},
  {"JK03-south", {474767, 396900}, {673217, 100350}},
  {"JT03_SGW", {723392, 374850}, {910817, 128464}},
  {"JT02_SMB", {975049, 242550}, {1096324, 112764}},
  {"takasaki", {1152706, 374850}, {1340131, 69788}},
};
constexpr size_t TRACK_COUNT = sizeof(TRACKS)/sizeof(TRACKS[0]);
constexpr uint32_t AUDIO_BYTES = 1375025;
constexpr uint32_t AUDIO_CRC = 0xe08368ea;
