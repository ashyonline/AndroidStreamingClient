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

import android.media.MediaFormat;

import com.biasedbit.efflux.packet.DataPacket;
import com.biasedbit.efflux.participant.RtpParticipantInfo;
import com.biasedbit.efflux.session.RtpSession;
import com.biasedbit.efflux.session.RtpSessionDataListener;
import com.c77.androidstreamingclient.lib.BufferedSample;
import com.c77.androidstreamingclient.lib.RtpMediaDecoder;
import com.c77.androidstreamingclient.lib.RtpPlayerException;
import com.c77.androidstreamingclient.lib.video.Decoder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.buffer.ChannelBuffer;

import java.nio.ByteBuffer;

/**
 * Created by julian on 12/12/14.
 */
public class OriginalRtpMediaExtractor implements RtpSessionDataListener, MediaExtractor {
    private static Log log = LogFactory.getLog(OriginalRtpMediaExtractor.class);

    public static final String CSD_0 = "csd-0";
    public static final String CSD_1 = "csd-1";
    public static final String DURATION_US = "durationUs";


    // Extractor settings
    //   Whether to use Byte Stream Format (H.264 spec., annex B)
    //   (prepends the byte stream 0x00000001 to each NAL unit)
    private boolean useByteStreamFormat = true;

    private final byte[] byteStreamStartCodePrefix = {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01};

    private final Decoder decoder;

    private int lastSequenceNumber = 0;
    private boolean lastSequenceNumberIsValid = false;
    private boolean sequenceError = false;

    private boolean currentFrameHasError = false;
    private BufferedSample currentFrame;

    public OriginalRtpMediaExtractor(Decoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public void dataPacketReceived(RtpSession session, RtpParticipantInfo participant, DataPacket packet) {
        String debugging = "RTP data. ";
        debugging += packet.getDataSize() + "b ";
        debugging += "#" + packet.getSequenceNumber();
        debugging += " " + packet.getTimestamp();

        if (lastSequenceNumberIsValid && (lastSequenceNumber + 1) != packet.getSequenceNumber()) {
            sequenceError = true;
            debugging += " SKIPPED (" + (packet.getSequenceNumber() - lastSequenceNumber - 1) + ")";
        } else {
            sequenceError = false;
        }

        if (RtpMediaDecoder.DEBUGGING) {
            log.error(debugging);
        }

        // Parsing the RTP Packet - http://www.ietf.org/rfc/rfc3984.txt section 5.3
        byte nalUnitOctet = packet.getData().getByte(0);
        byte nalFBits = (byte) (nalUnitOctet & 0x80);
        byte nalNriBits = (byte) (nalUnitOctet & 0x60);
        byte nalType = (byte) (nalUnitOctet & 0x1F);

        // If it's a single NAL packet then the entire payload is here
        if (nalType > 0 && nalType < 24) {
            if (RtpMediaDecoder.DEBUGGING) {
                log.info("NAL: full packet");
            }
            // Send the buffer upstream for processing

            startFrame(packet.getTimestamp());
            if (currentFrame != null) {

                if (useByteStreamFormat) {
                    currentFrame.getBuffer().put(byteStreamStartCodePrefix);
                }
                currentFrame.getBuffer().put(packet.getData().toByteBuffer());
                sendFrame();
            }
            // It's a FU-A unit, we should aggregate packets until done
        } else if (nalType == 28) {
            if (RtpMediaDecoder.DEBUGGING) {
                log.info("NAL: FU-A fragment");
            }
            byte fuHeader = packet.getData().getByte(1);

            boolean fuStart = ((fuHeader & 0x80) != 0);
            boolean fuEnd = ((fuHeader & 0x40) != 0);
            byte fuNalType = (byte) (fuHeader & 0x1F);

            // Do we have a clean start of a frame?
            if (fuStart) {
                if (RtpMediaDecoder.DEBUGGING) {
                    log.info("FU-A start found. Starting new frame");
                }

                startFrame(packet.getTimestamp());

                if (currentFrame != null) {
                    // Add stream header
                    if (useByteStreamFormat) {
                        currentFrame.getBuffer().put(byteStreamStartCodePrefix);
                    }

                    // Re-create the H.264 NAL header from the FU-A header
                    // Excerpt from the spec:
                /* "The NAL unit type octet of the fragmented
                   NAL unit is not included as such in the fragmentation unit payload,
                   but rather the information of the NAL unit type octet of the
                   fragmented NAL unit is conveyed in F and NRI fields of the FU
                   indicator octet of the fragmentation unit and in the type field of
                   the FU header"  */
                    byte reconstructedNalTypeOctet = (byte) (fuNalType | nalFBits | nalNriBits);
                    currentFrame.getBuffer().put(reconstructedNalTypeOctet);
                }
            }

            // if we don't have a buffer here, it means that we skipped the start packet for this
            // NAL unit, so we can't do anything other than discard everything else
            if (currentFrame != null) {

                // Did we miss packets in the middle of a frame transition?
                // In that case, I don't think there's much we can do other than flush our buffer
                // and discard everything until the next buffer
                if (packet.getTimestamp() != currentFrame.getRtpTimestamp()) {
                    if (RtpMediaDecoder.DEBUGGING) {
                        log.warn("Non-consecutive timestamp found");
                    }

                    currentFrameHasError = true;
                }
                if (sequenceError) {
                    currentFrameHasError = true;
                }

                // If we survived possible errors, collect data to the current frame buffer
                if (!currentFrameHasError) {
                    currentFrame.getBuffer().put(packet.getData().toByteBuffer(2, packet.getDataSize() - 2));
                } else if (RtpMediaDecoder.DEBUGGING) {
                    log.info("Dropping frame");
                }

                if (fuEnd) {
                    if (RtpMediaDecoder.DEBUGGING) {
                        log.info("FU-A end found. Sending frame!");
                    }
                    try {
                        sendFrame();
                    } catch (Throwable t) {
                        log.error("Error sending frame.", t);
                    }
                }
            }

            // STAP-A, used by libstreaming to embed SPS and PPS into the video stream
        } else if (nalType == 24) {
            if (RtpMediaDecoder.DEBUGGING) {
                log.info("NAL: STAP-A");
            }
            // This frame type includes a series of concatenated NAL units, each preceded
            // by a 16-bit size field

            // We'll use the reader index in this parsing routine
            ChannelBuffer buffer = packet.getData();
            // Discard the first byte (RTP packet type / nalType came from there)
            buffer.readByte();

            while (buffer.readable()) {
                // NAL Unit Size
                short nalUnitSize = buffer.readShort();

                // NAL Unit Data (of the size read above)
                byte[] nalUnitData = new byte[nalUnitSize];
                buffer.readBytes(nalUnitData);

                // Create and send the buffer upstream for processing
                startFrame(packet.getTimestamp());

                if (currentFrame != null) {
                    if (useByteStreamFormat) {
                        currentFrame.getBuffer().put(byteStreamStartCodePrefix);
                    }
                    currentFrame.getBuffer().put(nalUnitData);
                    sendFrame();
                }
            }

            // libstreaming doesn't use anything else, so we won't implement other NAL unit types, at
            // least for now
        } else {
            log.warn("NAL: Unimplemented unit type: " + nalType);
        }

        lastSequenceNumber = packet.getSequenceNumber();
        lastSequenceNumberIsValid = true;
    }

    private void startFrame(long rtpTimestamp) {
        // Reset error bit
        currentFrameHasError = false;

        // Deal with potentially non-returned buffer due to error
        if (currentFrame != null) {
            currentFrame.getBuffer().clear();
            // Otherwise, get a fresh buffer from the codec
        } else {
            try {
                // Get buffer from decoder
                currentFrame = decoder.getSampleBuffer();
                currentFrame.getBuffer().clear();

            } catch (RtpPlayerException e) {
                // TODO: Proper error handling
                currentFrameHasError = true;
                e.printStackTrace();
            }
        }

        if (!currentFrameHasError) {
            // Set the sample timestamp
            currentFrame.setRtpTimestamp(rtpTimestamp);
        }
    }

    private void sendFrame() {
        currentFrame.setSampleSize(currentFrame.getBuffer().position());
        currentFrame.getBuffer().flip();


        try {
            decoder.decodeFrame(currentFrame);
        } catch (Exception e) {
            log.error("Exception sending frame to decoder", e);
        }

        // Always make currentFrame null to indicate we have returned the buffer to the codec
        currentFrame = null;
    }

    public boolean isUseByteStreamFormat() {
        return useByteStreamFormat;
    }

    public void setUseByteStreamFormat(boolean useByteStreamFormat) {
        this.useByteStreamFormat = useByteStreamFormat;
    }

    // Think how to get CSD-0/CSD-1 codec-specific data chunks
    public MediaFormat getMediaFormat() {
        String mimeType = "video/avc";
        int width = 640;
        int height = 480;

        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        /*
        // the one got from internet
        byte[] header_sps = { 0, 0, 0, 1, // header
                0x67, 0x42, 0x00, 0x1f, (byte)0xe9, 0x01, 0x68, 0x7b, (byte) 0x20 }; // sps
        byte[] header_pps = { 0, 0, 0, 1, // header
                0x68, (byte)0xce, 0x06, (byte)0xf2 }; // pps

        // the one got from libstreaming at HQ
        byte[] header_sps = { 0, 0, 0, 1, // header
                0x67, 0x42, (byte)0x80, 0x14, (byte)0xe4, 0x40, (byte)0xa0, (byte)0xfd, 0x00, (byte)0xda, 0x14, 0x26, (byte)0xa0}; // sps
        byte[] header_pps = { 0, 0, 0, 1, // header
                0x68, (byte)0xce, 0x38, (byte)0x80 }; // pps


        // the one got from libstreaming at home
        byte[] header_sps = { 0, 0, 0, 1, // header
                0x67, 0x42, (byte) 0xc0, 0x1e, (byte) 0xe9, 0x01, 0x40, 0x7b, 0x40, 0x3c, 0x22, 0x11, (byte) 0xa8}; // sps
        byte[] header_pps = { 0, 0, 0, 1, // header
                0x68, (byte) 0xce, 0x06, (byte) 0xe2}; // pps
         */

        // from avconv, when streaming sample.h264.mp4 from disk
        byte[] header_sps = {0, 0, 0, 1, // header
                0x67, 0x64, (byte) 0x00, 0x1e, (byte) 0xac, (byte) 0xd9, 0x40, (byte) 0xa0, 0x3d,
                (byte) 0xa1, 0x00, 0x00, (byte) 0x03, 0x00, 0x01, 0x00, 0x00, 0x03, 0x00, 0x3C, 0x0F, 0x16, 0x2D, (byte) 0x96}; // sps
        byte[] header_pps = {0, 0, 0, 1, // header
                0x68, (byte) 0xeb, (byte) 0xec, (byte) 0xb2, 0x2C}; // pps


        format.setByteBuffer(CSD_0, ByteBuffer.wrap(header_sps));
        format.setByteBuffer(CSD_1, ByteBuffer.wrap(header_pps));

        //format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
        format.setInteger(DURATION_US, 12600000);

        return format;
    }
}
