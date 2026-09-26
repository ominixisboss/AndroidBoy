/*
 * RetroAchievements, through the rcheevos client library. The Java side (Achievements.java)
 * drives it: logging in, loading games, and making the web requests rcheevos asks for.
 */
#ifndef ANDROIDBOY_ACHIEVEMENTS_H
#define ANDROIDBOY_ACHIEVEMENTS_H

/* Checks achievements against the frame just emulated. Call on the emulation thread. */
void achievements_do_frame(void);

#endif
