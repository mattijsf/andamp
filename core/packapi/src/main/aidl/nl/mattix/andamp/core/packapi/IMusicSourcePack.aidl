// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.packapi;

import nl.mattix.andamp.core.packapi.IPackListener;
import nl.mattix.andamp.core.packapi.PackAccount;
import nl.mattix.andamp.core.packapi.PackAnswer;
import nl.mattix.andamp.core.packapi.PackDescriptor;
import nl.mattix.andamp.core.packapi.PackQuestion;
import nl.mattix.andamp.core.packapi.PackTrack;

/**
 * A music source that lives in its own APK, as the player reaches it.
 *
 * The player never runs a pack's code: it binds this, sends verbs and reads
 * back what the pack says. A source's playback, its library and its account
 * are all reached through this interface. The pack's settings are a screen of
 * its own.
 *
 * The verbs are oneway: a transport press does not wait on another process,
 * and its result arrives on IPackListener like every other state change. The
 * questions block for their answer; the player asks them off the main thread,
 * and the answers come in pages because a binder transaction is limited to
 * about a megabyte.
 */
interface IMusicSourcePack {
    /**
     * Which contract this pack implements: the PackApi.PACK_API it was built
     * with. The player calls this first and refuses a pack whose number differs
     * from its own.
     */
    int apiVersion();

    /** Who this source is and what it can answer; see PackDescriptor. */
    PackDescriptor describe();

    /** Whether this phone holds an account for it, and who that is. */
    PackAccount account();

    /** Registers a listener for what this pack says about playback and its account, until stopListening. */
    void listen(IPackListener listener);

    void stopListening(IPackListener listener);

    oneway void setQueue(in List<PackTrack> tracks, int startIndex);

    oneway void enqueue(in List<PackTrack> tracks);

    oneway void patchTracks(in List<PackTrack> tracks);

    oneway void play();

    oneway void pause();

    oneway void stop();

    oneway void next();

    oneway void previous();

    oneway void playAt(int index);

    oneway void seekTo(long positionMs);

    oneway void setVolume(float fraction);

    /** Whose volume the slider moves, by the ordinal of VolumeMode: 0 is DEVICE, 1 is APP. */
    oneway void setVolumeMode(int mode);

    oneway void setShuffle(boolean on);

    oneway void setRepeat(boolean on);

    oneway void setStopAfterCurrent(boolean on);

    oneway void stopWithFadeout();

    /** The player is quitting: nothing of this pack keeps running. */
    oneway void teardown();

    /** This player is done with the pack; it may be asked to play again later. */
    oneway void release();

    /** One page of one question; see PackQuestion and PackAnswer. */
    PackAnswer ask(in PackQuestion question);

    /**
     * The read end of a pipe carrying what the pack has decoded, or null from a
     * pack that plays its own audio.
     *
     * The player renders the samples, which gives the source the player's
     * equalizer, effect rack and visualizer. The pipe carries raw frames in the
     * format the descriptor names, with no framing.
     *
     * The end of the pipe is the end of that stretch of music. When the pack
     * drops audio (a seek, another track, a stop) it closes its end, so that
     * everything already written is dropped with it. The player then calls
     * openAudio again and gets a pipe that starts where the music now is.
     */
    ParcelFileDescriptor openAudio();
}
