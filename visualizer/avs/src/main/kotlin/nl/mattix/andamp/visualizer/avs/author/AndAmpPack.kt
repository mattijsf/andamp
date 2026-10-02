// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs.author

import nl.mattix.andamp.visualizer.avs.AvsPreset

/**
 * The pack this app ships: original presets, written here in code and
 * serialized through [AvsWriter]. Winamp's own presets and the packs listeners
 * import stay under their authors' terms (NOTICE.md).
 *
 * Each preset is a component stack: a trail stage (feedback, blur, fade),
 * renderers on top, color stages after, tuned for the 196-wide frame `AvsView`
 * renders. The host installs [files] into the preset library under [NAME],
 * and [VERSION] changes when the set changes.
 */
object AndAmpPack {
    const val NAME = "AndAmp"
    const val VERSION = 1

    /** Filename to file bytes, in the order the pack plays unshuffled. */
    fun files(): Map<String, ByteArray> = PRESETS.mapValues { AvsWriter.write(it.value) }

    private val PRESETS: Map<String, AvsPreset> =
        linkedMapOf(
            "andamp - neon cage.avs" to neonCage(),
            "andamp - lissajous silk.avs" to lissajousSilk(),
            "andamp - starburst.avs" to starburst(),
            "andamp - ion tunnel.avs" to ionTunnel(),
            "andamp - magma bore.avs" to magmaBore(),
            "andamp - warp gate.avs" to warpGate(),
            "andamp - neon spokes.avs" to neonSpokes(),
            "andamp - borealis.avs" to borealis(),
            "andamp - bloom reactor.avs" to bloomReactor(),
            "andamp - event horizon.avs" to eventHorizon(),
            "andamp - ion lattice.avs" to ionLattice(),
            "andamp - dynamo.avs" to dynamo(),
        )
}

/**
 * A wireframe cube in perspective inside a rotating feedback echo. Beats
 * enlarge it, kick the spin, and reverse the echo.
 */
private fun neonCage(): AvsPreset =
    AvsAuthor.preset(
        rotoBlitter(zoom = 33, rotate = 35, onBeatReverse = 1, reversalSpeed = 1, beatZoom = 26, onBeat = 1),
        blur(level = 2),
        fadeOut(speed = 12),
        setRenderMode(blend = 1),
        superScope(
            init =
                """
                n=384;
                rx=0.62; ry=0.21;
                kick=1; krx=0; kry=0
                """.trimIndent(),
            frame =
                """
                rx=rx+0.011+krx;
                ry=ry+0.0163+kry;
                krx=krx*0.9;
                kry=kry*0.9;
                kick=1+(kick-1)*0.88;
                cx=cos(rx); sx=sin(rx);
                cy=cos(ry); sy=sin(ry);
                m00=cy; m01=sx*sy; m02=cx*sy;
                m11=cx; m12=-sx;
                m20=-sy; m21=sx*cy; m22=cx*cy;
                sc=0.5*kick;
                ha=h/w;
                cr=red; cg=green; cb=blue
                """.trimIndent(),
            beat = "kick=1.42;\nkrx=0.05;\nkry=-0.041",
            point =
                """
                k=floor(i*383+0.5);
                e=floor(k/32);
                j=k-e*32;
                p=j/15.5-1;
                axis=floor(e/4);
                c=e-axis*4;
                u=(c%2)*2-1;
                f2=(floor(c/2)%2)*2-1;
                a0=equal(axis,0);
                a1=equal(axis,1);
                a2=1-a0-a1;
                px=a0*p+(1-a0)*u;
                py=a1*p+a0*u+a2*f2;
                pz=a2*p+(1-a2)*f2;
                wob=1+0.22*abs(v)*abs(p);
                px=px*wob; py=py*wob; pz=pz*wob;
                tx=(px*m00+py*m01+pz*m02)*sc;
                ty=(py*m11+pz*m12)*sc;
                tz=(px*m20+py*m21+pz*m22)*sc;
                cz=max(tz+2.6,0.35);
                x=tx*1.6/cz*ha;
                y=ty*1.6/cz;
                fog=max(0.12,min(1,(3.3-cz)*0.7));
                red=cr*fog; green=cg*fog; blue=cb*fog;
                skip=below(j,0.5)
                """.trimIndent(),
            flags = 2,
            colours = listOf(0x00E8FF, 0xFF3CC8),
        ),
    )

/**
 * A 3D Lissajous knot re-tying itself in time with the music, over a drifting
 * navy dot lattice, old curves swallowed into a tunnel behind it.
 */
private fun lissajousSilk(): AvsPreset =
    AvsAuthor.preset(
        movementBuiltin(effect = 12), // Tunneling
        blur(level = 2, roundUp = 1),
        fadeOut(speed = 9),
        dotGrid(colours = listOf(0x1A2A50), spacing = 14, speedX = 96, speedY = -64),
        setRenderMode(blend = 1),
        superScope(
            init =
                """
                n=360;
                fa=2; fb=3; fc=1;
                ta=3; tb=4; tc=2;
                pick=1;
                p1=0; p2=0; p3=0;
                amp=1; kp=0;
                rx=0.4; ry=0.9
                """.trimIndent(),
            frame =
                """
                p1=p1+0.019+kp;
                p2=p2+0.023+kp;
                p3=p3+0.011+kp;
                kp=kp*0.9;
                fa=fa+(ta-fa)*0.04;
                fb=fb+(tb-fb)*0.04;
                fc=fc+(tc-fc)*0.04;
                amp=1+(amp-1)*0.9;
                rx=rx+0.007;
                ry=ry+0.0093;
                cx=cos(rx); sx=sin(rx);
                cy=cos(ry); sy=sin(ry);
                m00=cy; m01=sx*sy; m02=cx*sy;
                m11=cx; m12=-sx;
                m20=-sy; m21=sx*cy; m22=cx*cy;
                ha=h/w;
                cr=red; cg=green; cb=blue
                """.trimIndent(),
            beat =
                """
                pick=(pick+1)%4;
                q0=equal(pick,0);
                q1=equal(pick,1);
                q2=equal(pick,2);
                q3=1-q0-q1-q2;
                ta=q0*2+q1*3+q2*5+q3*3;
                tb=q0*3+q1*4+q2*2+q3*5;
                tc=q0*1+q1*2+q2*3+q3*4;
                amp=1.35;
                kp=0.05
                """.trimIndent(),
            point =
                """
                k=floor(i*359+0.5);
                st=floor(k/180);
                j=k-st*180;
                q=j/179;
                th=q*6.283185;
                off=st*0.22;
                sx1=sin(fa*th+p1+off);
                sy1=sin(fb*th+p2+off*1.3);
                sz1=sin(fc*th+p3-off);
                r1=(0.72+0.05*v)*amp;
                px=sx1*r1;
                py=sy1*r1*0.8;
                pz=sz1*r1;
                tx=px*m00+py*m01+pz*m02;
                ty=py*m11+pz*m12;
                tz=px*m20+py*m21+pz*m22;
                cz=max(tz+2.2,0.35);
                x=tx*1.55/cz*ha;
                y=ty*1.55/cz;
                fog=max(0.1,min(1,(2.9-cz)*0.8));
                fog=fog*(1-0.25*st);
                red=cr*fog; green=cg*fog; blue=cb*fog;
                skip=below(j,0.5)
                """.trimIndent(),
            flags = 2,
            colours = listOf(0xFFB428, 0x28E0C8),
        ),
    )

/**
 * A two-armed wheel of 120 waveform-read rays around an open hub, over a
 * night-blue floor. Every eighth beat fires a 50/50 white flash that expands
 * away through the feedback.
 */
private fun starburst(): AvsPreset {
    val rays =
        """
        k=floor(i*239+0.5);
        ray=floor(k/2);
        tip=k%2;
        fr=ray/119;
        sv=min(1,abs(v)*1.2);
        an=fr*12.56637+rot;
        len=0.10+0.05*sin(bt*1.7+fr*18.85)+0.80*sv*pk;
        rr=0.14+tip*len;
        x=cos(an)*rr*ha;
        y=sin(an)*rr;
        sv2=min(1,sv*pk);
        """.trimIndent()
    val spin =
        """
        bt=bt+0.021;
        rot=rot+(0.0065+vk)*sdir;
        vk=vk*0.86;
        pk=1+(pk-1)*0.87;
        ha=h/w
        """.trimIndent()
    return AvsAuthor.preset(
        onBeatClear(colour = 0xFFFFFF, blend = 1, beats = 8),
        blitterFeedback(zoom = 28, beatZoom = 22),
        blur(level = 1),
        fadeOut(speed = 15, colour = 0x000914),
        setRenderMode(blend = 1),
        superScope(
            init = "n=240;\nrot=0; sdir=1;\npk=1; vk=0; bt=0",
            frame = spin,
            beat = "sdir=-sdir;\nvk=0.11;\npk=1.55",
            point = rays + "\nred=0.55+0.45*sv2;\ngreen=0.34+0.62*sv2;\nblue=0.18+0.82*sv2;\nskip=1-tip",
            flags = 2,
            colours = listOf(0xFFC864),
        ),
    )
}

/**
 * A white-hot ion ring shedding cyan light down an endlessly expanding tunnel
 * with a differential swirl. Beats slam the swirl (sometimes reversing it) and
 * flash the ring white.
 */
private fun ionTunnel(): AvsPreset =
    AvsAuthor.preset(
        dynamicMovement(
            init = "kick=0;dir=1;zm=0.962;tw=0.02",
            frame = "kick=kick*0.93;zm=0.962-0.030*kick;tw=dir*(0.020+0.080*kick)",
            beat = "kick=1;dir=if(above(rand(4),0),dir,-dir)",
            point = "d=d*zm;r=r+tw*(1.35-d)",
            coordinates = 0,
            gridWidth = 24,
            gridHeight = 18,
        ),
        blur(level = 2),
        fadeOut(speed = 11),
        setRenderMode(blend = 1),
        superScope(
            init = "n=180;t=0;kick=0",
            frame =
                """
                kick=kick*0.90;
                t=t+0.03+0.09*kick;
                rad=0.20+0.06*sin(t*0.47)+0.22*kick;
                red=0.10+0.10*sin(t*0.40);
                green=0.55+0.25*sin(t*0.31+2.0);
                blue=1.0;
                red=red+kick*(1-red);
                green=green+kick*(1-green);
                blue=blue+kick*(1-blue)
                """.trimIndent(),
            beat = "kick=1",
            point = "a=i*6.283+t*0.5;rr=rad+0.10*v;x=cos(a)*rr*0.75;y=sin(a)*rr",
            flags = 2,
            colours = listOf(0x40E0FF),
        ),
        movingParticle(colour = 0x66C8FF, distance = 14, size = 6, beatSize = 26),
        colorClipBelow(against = 0x101010),
    )

/**
 * A whirlpool dragging everything into a dark center over an ember floor,
 * with molten bass arms and a golden particle burning at the core.
 */
private fun magmaBore(): AvsPreset =
    AvsAuthor.preset(
        movementScript(code = "r=r-0.055*(1.35-d);d=d*1.045+0.002"),
        blitterFeedback(zoom = 32, beatZoom = 25),
        fadeOut(speed = 13, colour = 0x180000),
        bassSpin(colourLeft = 0xFF2A00, colourRight = 0xFF8C1E),
        movingParticle(colour = 0xFFB030, distance = 20, size = 7, beatSize = 36),
        grain(amount = 7),
        colorModifier(point = "red=red*(0.60+0.40*red);green=green*green*(0.85+0.15*green);blue=blue*blue*0.70"),
    )

/**
 * Wireframe rings flying out of a drifting vanishing point through a 50/50
 * spiral feedback, white stars streaming past; beats split the frame into a
 * brief RGB-separated bloom.
 */
private fun warpGate(): AvsPreset =
    AvsAuthor.preset(
        rotoBlitter(zoom = 29, rotate = 34, onBeatReverse = 1, reversalSpeed = 2, beatZoom = 25, onBeat = 1),
        blur(level = 2),
        setRenderMode(blend = 1),
        superScope(
            init = "n=360;t=0;kick=0",
            frame =
                """
                kick=kick*0.92;
                t=t+0.011+0.028*kick;
                c0r=0.85+0.15*kick;
                c0g=0.45+0.55*kick;
                c0b=1.0;
                c1r=0.45+0.55*kick;
                c1g=1.0;
                c1b=0.65+0.35*kick
                """.trimIndent(),
            beat = "kick=1",
            point =
                """
                ri=floor(i*2.999);
                f=i*2.999-ri;
                a=f*6.283;
                p=t*0.9+ri*0.3333;
                p=p-floor(p);
                rr=0.06+p*p*1.15+0.05*v;
                q=1-p;
                cx=0.22*sin(t*0.8+ri*2.1)*q;
                cy=0.18*cos(t*0.63+ri*2.1)*q;
                x=(cos(a)*rr+cx)*0.75;
                y=sin(a)*rr+cy;
                g=0.35+0.65*p;
                sel=ri-floor(ri*0.5)*2;
                red=g*if(equal(sel,0),c0r,c1r);
                green=g*if(equal(sel,0),c0g,c1g);
                blue=g*if(equal(sel,0),c0b,c1b);
                skip=below(f,0.012)
                """.trimIndent(),
            flags = 2,
            colours = listOf(0xFFFFFF),
        ),
        starfield(colour = 0xFFFFFF, warpSpeed = 5f, stars = 1800, onBeat = 1, beatSpeed = 13f, beatFrames = 18),
        interferences(
            layers = 3,
            distance = 2,
            alpha = 255,
            rotation = 2,
            additive = 0,
            onBeatDistance = 14,
            onBeatAlpha = 200,
            onBeatRotation = 40,
            onBeatSpeed = 0.12f,
        ),
        colorClipBelow(against = 0x060606),
    )

/**
 * A circular analyzer of 120 radial spikes riding the waveform, glowing cyan
 * over an outward-blooming feedback tunnel with a magenta particle core.
 */
private fun neonSpokes(): AvsPreset =
    AvsAuthor.preset(
        fadeOut(speed = 12),
        blitterFeedback(zoom = 29, beatZoom = 18),
        blur(level = 2, roundUp = 1),
        setRenderMode(blend = 1),
        superScope(
            init = "n=240; t=0; kick=0;",
            frame = "t=t+0.008+kick*0.04; kick=kick*0.88;",
            beat = "kick=1;",
            point =
                """
                idx=floor(i*239.999);
                bar=floor(idx*0.5);
                tip=idx-bar*2;
                a=bar*0.05236+t;
                va=abs(v);
                rr=0.30+0.02*sin(a*3-t*2)+tip*(0.05+va*0.45+kick*0.12);
                x=cos(a)*rr*0.75;
                y=sin(a)*rr;
                skip=equal(tip,0);
                red=min(1,0.15+va*0.9+kick*0.4);
                green=min(1,0.5+kick*0.5);
                blue=1;
                """.trimIndent(),
            flags = 2,
            colours = listOf(0x40C8FF),
        ),
        ring(colours = listOf(0x3050FF), size = 9),
        movingParticle(colour = 0xFF50D0, distance = 10, size = 9, beatSize = 34),
    )

/**
 * A teal-green aurora line drawn by a Super Scope and smeared by a Dynamic
 * Movement over an indigo star field, its trail hue shifted by a Colorfade;
 * beats lift the line and strengthen the warp.
 */
private fun borealis(): AvsPreset =
    AvsAuthor.preset(
        fadeOut(speed = 5, colour = 0x020408),
        dynamicMovement(
            init = "t=0; warp=0;",
            frame = "t=t+0.015; warp=warp*0.92;",
            beat = "warp=min(warp+0.7,1.3);",
            point = "x=x+(0.006+0.028*warp)*sin(y*3.5+t*1.3+x*1.8); y=y+0.012+0.006*warp;",
            coordinates = 1,
            gridWidth = 24,
            gridHeight = 18,
        ),
        blur(level = 2),
        starfield(colour = 0x90A8FF, warpSpeed = 1.5f, stars = 900, onBeat = 1, beatSpeed = 4f, beatFrames = 30),
        setRenderMode(blend = 1),
        superScope(
            init = "n=196;t1=0;t2=0;lift=0",
            frame = "t1=t1+0.021;t2=t2+0.013;lift=lift*0.90",
            beat = "lift=0.35",
            point =
                """
                x=i*2-1;
                wave=sin(i*9.4+t1)*0.5+sin(i*17.3-t2)*0.3;
                y=0.15-0.35*lift-0.22*wave-0.25*abs(v);
                glow=0.55+0.45*wave;
                red=0.15*glow;
                green=0.75*glow+0.25*lift;
                blue=0.45+0.35*glow
                """.trimIndent(),
            flags = 2,
            colours = listOf(0x40FF9C),
        ),
        colorfade(fader2nd = 4, faderMax = -6, fader3rdGray = -8, beat2nd = 8, beatMax = -12, beat3rdGray = -10),
    )

/**
 * A hot magenta-amber rose with a waveform-rippled edge breathing inside a
 * spinning spiral of its own trails, fringed by an RGB-split halo; every beat
 * re-rolls the petal count, every fourth flashes half-white.
 */
private fun bloomReactor(): AvsPreset =
    AvsAuthor.preset(
        fadeOut(speed = 7),
        rotoBlitter(zoom = 30, rotate = 35, onBeatReverse = 1, reversalSpeed = 2, beatZoom = 22, onBeat = 1),
        onBeatClear(colour = 0xFFFFFF, blend = 1, beats = 4),
        setRenderMode(blend = 1),
        superScope(
            init = "n=220; env=0.45; rot=0; ph=0; k=4; t=0;",
            frame = "t=t+0.011; env=max(env*0.93,0.40); rot=rot+0.006+env*0.03;",
            beat = "env=1; k=3+floor(rand(4)); ph=ph+0.9;",
            point =
                """
                a=i*6.2832;
                va=abs(v);
                rr=env*(0.2+0.3*va)*(0.55+0.45*cos(a*k+ph));
                x=cos(a+rot)*rr*0.75;
                y=sin(a+rot)*rr;
                red=1;
                green=0.2+env*0.55;
                blue=0.25+va*0.6;
                """.trimIndent(),
            flags = 2,
            colours = listOf(0xFF60A0),
        ),
        interferences(
            layers = 3,
            distance = 2,
            alpha = 255,
            rotation = 2,
            additive = 0,
            onBeatDistance = 22,
            onBeatAlpha = 200,
            onBeatRotation = 40,
            onBeatSpeed = 0.12f,
        ),
        grain(amount = 12),
        colorClipBelow(against = 0x181818),
    )

/**
 * A cold ice-blue star warp pouring through a half-mixed feedback tunnel whose
 * trail history hue-drifts, one ember-orange particle riding the horizon.
 */
private fun eventHorizon(): AvsPreset =
    AvsAuthor.preset(
        blitterFeedback(zoom = 27, beatZoom = 10),
        fadeOut(speed = 10),
        colorfade(fader2nd = -6, faderMax = 4, fader3rdGray = -10, beat2nd = -10, beatMax = 8, beat3rdGray = -14),
        starfield(colour = 0xC8E8FF, warpSpeed = 5f, stars = 1800, onBeat = 1, beatSpeed = 14f, beatFrames = 18),
        movingParticle(colour = 0xFF7818, distance = 20, size = 9, beatSize = 42),
        colorClipBelow(against = 0x0C0C10),
    )

/**
 * Two neon dot lattices drifting through each other over a spiraling 50/50
 * wash, weaving a moire field around a color-cycling orbit ribbon; beats
 * re-roll the trail history through a random channel permutation.
 */
private fun ionLattice(): AvsPreset =
    AvsAuthor.preset(
        rotoBlitter(zoom = 31, rotate = 34, onBeatReverse = 1, reversalSpeed = 2, beatZoom = 31, onBeat = 0),
        fadeOut(speed = 8),
        blur(level = 2),
        channelShift(mode = 1018, onBeatRandom = 1),
        dotGrid(colours = listOf(0x00E0FF, 0x0080FF), spacing = 11, speedX = 160, speedY = -96),
        dotGrid(colours = listOf(0xFF30C0, 0xB030FF), spacing = 13, speedX = -224, speedY = 64),
        superScope(
            init = "n=160;t=0;kick=0;drift=0.011",
            frame = "t=t+drift+kick*0.02;kick=kick*0.90",
            beat = "kick=1",
            point =
                """
                ph=i*6.283;
                r=0.5+0.1*sin(ph*3+t*2.6)+0.22*kick*sin(ph*5-t*8)+v*0.1;
                x=0.75*r*cos(ph+t);
                y=r*sin(ph+t);
                red=0.6+0.4*sin(ph+t);
                green=0.35+0.35*sin(ph*2-t*1.7);
                blue=1
                """.trimIndent(),
            flags = 2,
            colours = listOf(0xFFFFFF),
        ),
    )

/**
 * Two filled bass arms whipping around a white audio-scaled ring, sparks
 * draining inward through a swirl, every trail rainbow-fringed by a rotating
 * RGB split, over a faint drifting field of steel-blue dots.
 */
private fun dynamo(): AvsPreset =
    AvsAuthor.preset(
        movementBuiltin(effect = 6), // Swirl To Center
        blur(level = 2, roundUp = 1),
        fadeOut(speed = 7),
        dotGrid(colours = listOf(0x182840), spacing = 16, speedX = 48, speedY = -32),
        bassSpin(colourLeft = 0xFFA000, colourRight = 0x00C8FF),
        ring(colours = listOf(0xFFFFFF, 0xFFD890), size = 13),
        interferences(
            layers = 3,
            distance = 5,
            alpha = 255,
            rotation = 2,
            additive = 0,
            onBeatDistance = 22,
            onBeatAlpha = 255,
            onBeatRotation = 12,
            onBeatSpeed = 0.1f,
        ),
        colorClipBelow(against = 0x101010),
    )
