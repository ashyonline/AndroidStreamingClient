/*
* Copyright (C) 2015 Creativa77 SRL and others
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
* Contributors:
*
* Ayelen Chavez ashi@creativa77.com.ar
* Julian Cerruti jcerruti@creativa77.com.ar
*
*/

package com.c77.androidstreamingclient.lib.rtp;

import com.biasedbit.efflux.packet.DataPacket;
import com.biasedbit.efflux.participant.RtpParticipantInfo;
import com.biasedbit.efflux.session.RtpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Properties;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Created by ashi on 1/13/15.
 */
public class RtpMediaBufferWithJitterAvoidance implements RtpMediaBuffer {
    public static final String DEBUGGING_PROPERTY = "DEBUGGING";
    public static final java.lang.String FRAMES_WINDOW_PROPERTY = "FRAMES_WINDOW_K";
    private State streamingState;
    private long lastTimestamp;

    ConcurrentSkipListMap.Entry<Long, Frame> currentFrameEntry;

    // debugging variables
    int totalFrames = 0;
    int framesSent = 0;
    int totalLoops = 0;
    int tooMuchTimeLoops = 0;

    protected enum State {
        IDLE,       // Just started. Didn't receive any packets yet
        WAITING,    // Wait until there are enough frames
        STREAMING   // Receiving packets
    }

    private static boolean DEBUGGING = false;
    private static long SENDING_DELAY = 28;
    private static int FRAMES = 50;

    private final RtpMediaExtractor rtpMediaExtractor;
    private final DataPacketSenderThread dataPacketSenderThread;
    // frames sorted by their timestamp
    ConcurrentSkipListMap<Long, Frame> frames = new ConcurrentSkipListMap<Long, Frame>();
    private Log log = LogFactory.getLog(RtpMediaBufferWithJitterAvoidance.class);

    RtpSession session;
    RtpParticipantInfo participant;

    public RtpMediaBufferWithJitterAvoidance(RtpMediaExtractor rtpMediaExtractor, Properties properties) {

        this.rtpMediaExtractor = rtpMediaExtractor;
        streamingState = State.IDLE;
        dataPacketSenderThread = new DataPacketSenderThread();

        properties = (properties != null) ? properties : new Properties();
        DEBUGGING = Boolean.parseBoolean(properties.getProperty(DEBUGGING_PROPERTY, "false"));
        FRAMES = Integer.parseInt(properties.getProperty(FRAMES_WINDOW_PROPERTY, "50"));
        log.info("Using RtpMediaBufferWithJitterAvoidance with FRAMES = [" + FRAMES + "]");
    }

    @Override
    public void dataPacketReceived(RtpSession session, RtpParticipantInfo participant, DataPacket packet) {
        if (DEBUGGING) {
            log.info("Packet Arriving");
        }

        if (streamingState == State.IDLE) {
            this.session = session;
            this.participant = participant;
            lastTimestamp = getConvertedTimestamp(packet);

            streamingState = State.WAITING;
        }

        // discard packets that are too late
        if (State.STREAMING == streamingState && getConvertedTimestamp(packet) < lastTimestamp) {
            if (DEBUGGING) {
                log.info("Discarded getPacket with timestamp " + getConvertedTimestamp(packet) + ", buffer size: " + frames.size());
            }
            return;
        }

        Frame frame = getFrameForPacket(packet);
        frames.put(new Long(frame.timestamp()), frame);

        // wait to have k frames to start streaming
        if (streamingState == State.WAITING && frames.size() >= FRAMES) {
            log.info("Start consuming!");
            streamingState = State.STREAMING;
            dataPacketSenderThread.start();
        }
    }

    private long getConvertedTimestamp(DataPacket packet) {
        return packet.getTimestamp() / 90;
    }

    private Frame getFrameForPacket(DataPacket packet) {
        Frame frame;
        long timestamp = getConvertedTimestamp(packet);
        if (frames.containsKey(timestamp)) {
            // if a frame with this timestamp already exists, add getPacket to it
            frame = frames.get(timestamp);
            // add getPacket to frame
            frame.addPacket(packet);
        } else {
            // if no frames with this timestamp exists, create a new one
            frame = new Frame(new DataPacketWithNalType(packet));
        }

        return frame;
    }

    @Override
    public void stop() {
        if (dataPacketSenderThread != null) {
            dataPacketSenderThread.shutdown();
        }
    }

    private class DataPacketSenderThread extends Thread {
        private boolean running = true;
        private long timeWhenCycleStarted;
        private long delay;

        @Override
        public void run() {
            super.run();

            Frame frame = null;

            while (running) {
                timeWhenCycleStarted = System.currentTimeMillis();
                // go through all the frames which timestamp is the range [downTimestampBound,upTimestampBound)

                currentFrameEntry = frames.firstEntry();

                if (currentFrameEntry != null) {
                    frame = currentFrameEntry.getValue();

                    totalFrames++;
                    if (frame.isCompleted()) {
                        sendFrame(frame);
                        framesSent++;
                    } else if (DEBUGGING) {
                        log.info("Discarded frame. It was not completed.");
                    }

                    // update timestamp of last frame sent or should have been sent
                    lastTimestamp = frame.timestamp();

                    frames.remove(currentFrameEntry.getKey());
                }

                waitForNextFrame();

                if (DEBUGGING) {
                    if (totalFrames % 100 == 0) {
                        log.info("Total Frames: " + totalFrames + " - Sent Ones: " + framesSent + " - ratio: " + framesSent / (float) totalFrames);
                        log.info("Total Loops: " + totalLoops + " - Too late ones: " + tooMuchTimeLoops);
                    }
                }
            }
        }

        private void sendFrame(Frame frame) {

            for (DataPacketWithNalType packet : frame.getPackets()) {
                rtpMediaExtractor.sendPacket(packet);
            }
        }

        private void waitForNextFrame() {
            try {
                delay = (System.currentTimeMillis() - timeWhenCycleStarted);

                long actualDelay = Math.max(SENDING_DELAY - delay, 0);

                if (actualDelay == 0) {
                    tooMuchTimeLoops++;
                }
                totalLoops++;

                sleep(actualDelay);
            } catch (InterruptedException e) {
                log.error("Error while waiting to send next frame", e);
            }
        }

        public void shutdown() {
            running = false;
        }
    }
}
