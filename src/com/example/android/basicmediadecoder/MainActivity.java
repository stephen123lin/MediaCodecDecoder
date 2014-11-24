/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.basicmediadecoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

/**
 * This activity uses a {@link android.view.TextureView} to render the frames of
 * a video decoded using {@link android.media.MediaCodec} API.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final String MEDIA_PATH = "/sdcard/chu.mp4";
    
    private TextureView mPlaybackView;
    private TextView mAttribView = null;
    private Handler mUIHandler = new Handler( getBackgroundLooper() );
    private Runnable mVideoEventRunnable = new Runnable() {
        @Override
        public void run() {
            runDecoder();
        }
    };
    private static Looper getBackgroundLooper() {
        HandlerThread handleThread = new HandlerThread( TAG );
        handleThread.start();
        return handleThread.getLooper();
    }

    private boolean mIsRunning;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_main);
        mPlaybackView = (TextureView) findViewById(R.id.PlaybackView);
        mAttribView = (TextView) findViewById(R.id.AttribView);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.action_menu, menu);
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsRunning = false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_play) {
            Log.v(TAG, "mVideoEventRunnable go");
            mAttribView.setVisibility(View.VISIBLE);
            mUIHandler.postDelayed(mVideoEventRunnable, 10);
            item.setEnabled(false);
        }
        return true;
    }

    private MediaExtractor extractorVideo;
    private MediaExtractor extractorAudio;
    private MediaCodec decoderVideo;
    private MediaCodec decoderAudio;
    private Surface surface;
    private AudioTrack audioTracker;
    private boolean isAudioNeedNext;
    
    private int sampleRate;
    private int channelCount;

    public void runDecoder() {
        Log.v(TAG, "runDecoder");
        extractorVideo = new MediaExtractor();
        extractorAudio = new MediaExtractor();
        try {
            extractorVideo.setDataSource(MEDIA_PATH);
            extractorAudio.setDataSource(MEDIA_PATH);
        } catch (IOException e1) {
            e1.printStackTrace();
            return;
        }

        mIsRunning = true;
        // FIXME:
        // debug
        boolean forceShow = false;
        boolean runAudio = true;
        long videoDelayMin = -10000l;
        long videoDelayMax = 30000l;
        long audioDelayMin = -10000l;
        long audioDelayMax = 30000l;        

        int videoTrackerIdx = -1;
        int audioTrackerIdx = -1;

        surface = new Surface(mPlaybackView.getSurfaceTexture());
        for (int i = 0; i < extractorVideo.getTrackCount(); i++) {
            MediaFormat format = extractorVideo.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            extractorVideo.unselectTrack(i);
            if (mime.startsWith("video/")) {
                decoderVideo = MediaCodec.createDecoderByType(mime);
                decoderVideo.configure(format, surface, null, 0);
                videoTrackerIdx = i;
                extractorVideo.selectTrack(i);
                break;
            }
        }
        int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        if (runAudio) {
            for (int i = 0; i < extractorAudio.getTrackCount(); i++) {
                MediaFormat format = extractorAudio.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                extractorAudio.unselectTrack(i);
                if (mime.startsWith("audio/")) {   
                    decoderAudio = MediaCodec.createDecoderByType(mime);
                    decoderAudio.configure(format, null, null, 0);
                    audioTrackerIdx = i;
                    extractorAudio.selectTrack(i);
                    
                    // TODO:
                    // init audio 
                    submittedBytes = 0;
                    temporaryBufferSize = 0;
                    lastRawPlaybackHeadPosition = 0;
                    rawPlaybackHeadWrapCount = 0;
                    sampleRate = format.getInteger( MediaFormat.KEY_SAMPLE_RATE );
                    channelCount = format.getInteger( MediaFormat.KEY_CHANNEL_COUNT );
                    frameSize = 2 * channelCount;                    
                    switch (channelCount) {
                      case 1:
                        channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                        break;
                      case 2:
                        channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                        break;
                      case 6:
                        channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                        break;
                    }
                    bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT);
                    Log.v( TAG, "sampleRate: " + sampleRate );
                    Log.v( TAG, "bufferSize: " + bufferSize );
                    break;
                }
            }
        }

        if (decoderVideo == null) {
            Log.e("DecodeActivity", "Can't find video info!");
            return;
        }
        if (runAudio && null == decoderAudio ) {
            Log.e("DecodeActivity", "Can't find audio info!");
            return;
        }
        audioTracker = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                channelConfig, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize, AudioTrack.MODE_STREAM);        
        audioTracker.play();

        Log.v(TAG, "proc: decoder start");
        decoderVideo.start();
        decoderAudio.start();

        ByteBuffer[] videoInputBuffers = decoderVideo.getInputBuffers();
        ByteBuffer[] videoOutputBuffers = decoderVideo.getOutputBuffers();
        ByteBuffer[] audioInputBuffers = decoderAudio.getInputBuffers();
        ByteBuffer[] audioOutputBuffers = decoderAudio.getOutputBuffers();
//        BufferInfo videoInfo = new BufferInfo();
//        BufferInfo audioInfo = new BufferInfo();
        boolean isEOS = false;
        long startedUs = 0;
        long videoTimestampUs = 0;
        long audioTimestampUs = 0; 

        Queue< Integer > vecVideoInIndex = new LinkedList< Integer >();
        Queue< Integer > vecVideoOutIndex = new LinkedList< Integer >();
        Queue< BufferInfo > vecVideoInfo = new LinkedList< BufferInfo >();
        
        Queue< Integer > vecAudioInIndex = new LinkedList< Integer >();
        Queue< Integer > vecAudioOutIndex = new LinkedList< Integer >();
        Queue< BufferInfo > vecAudioInfo = new LinkedList< BufferInfo >();

        Log.i(TAG, "videoTrackerIdx: " + videoTrackerIdx);
        Log.i(TAG, "audioTrackerIdx: " + audioTrackerIdx);
        
        int videoDequeuCounter = 0;
        int audioDequeuCounter = 0;
        
        isAudioNeedNext = true;
        int seekPosMs = 10000;

        while (mIsRunning) {
//            avaliableInput.dump();
//            avaliableOutput.dump();
            if (!isEOS) {
                videoDequeuCounter = 0;
                audioDequeuCounter = 0;
                do {
                    int videoInIdx = decoderVideo.dequeueInputBuffer(0);
                    if ( videoInIdx < 0 ) {
                        break;
                    }
                    vecVideoInIndex.offer( videoInIdx );
                    ++videoDequeuCounter;
                } while ( true );                
                do { 
                    int audioInIdx = decoderAudio.dequeueInputBuffer(0);
                    if ( audioInIdx < 0 ) {
                        break;
                    }
                    vecAudioInIndex.offer( audioInIdx );
                    ++audioDequeuCounter;
                } while ( true );
                
                Log.v( TAG, String.format( "dequeue input: %d %d", 
                    videoDequeuCounter, 
                    audioDequeuCounter )
                );
                Log.v( TAG, "vecVideoInIndex size: " + vecVideoInIndex.size() );
                Log.v( TAG, "vecAudioInIndex size: " + vecAudioInIndex.size() );
                videoDequeuCounter = 0;
                audioDequeuCounter = 0;
                              
                do { 
                    BufferInfo info = new BufferInfo();
                    int videoOutIdx = decoderVideo.dequeueOutputBuffer(info, 0);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.e( TAG, "BUFFER_FLAG_END_OF_STREAM" );
                    }
                    if ( videoOutIdx < 0 ) {
                        if ( MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED == videoOutIdx ) {
                            videoOutputBuffers = decoderVideo.getOutputBuffers();
                            vecVideoOutIndex.clear();
                            vecVideoInfo.clear();                            
                        }
                        else if ( MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == videoOutIdx ) {
                            Log.e( TAG, "INFO_OUTPUT_FORMAT_CHANGED" );
                        }
                        break;
                    }                    
                    ++videoDequeuCounter;
                    vecVideoOutIndex.offer( videoOutIdx );
                    vecVideoInfo.offer( info );
                } while ( true );
                do { 
                    BufferInfo info = new BufferInfo();
                    int audioOutIdx = decoderAudio.dequeueOutputBuffer(info, 0);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.e( TAG, "BUFFER_FLAG_END_OF_STREAM" );
                    }
                    if ( audioOutIdx < 0 ) {
                        if ( MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED == audioOutIdx ) {
                            audioOutputBuffers = decoderAudio.getOutputBuffers();
                            vecAudioOutIndex.clear();
                            vecAudioInfo.clear();                            
                        }
                        else if ( MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == audioOutIdx ) {
                            Log.e( TAG, "INFO_OUTPUT_FORMAT_CHANGED" );
                        }
                        break;
                    }
                    ++audioDequeuCounter;
                    vecAudioOutIndex.offer( audioOutIdx );
                    vecAudioInfo.offer( info );
                } while ( true );
                
                Log.v( TAG, String.format( "dequeue output: %d %d", 
                    videoDequeuCounter, 
                    audioDequeuCounter )
                );
                Log.v( TAG, "vecVideoOutIndex size: " + vecVideoOutIndex.size() );
                Log.v( TAG, "vecAudioOutIndex size: " + vecAudioOutIndex.size() );
                
                if ( seekPosMs != 0 ) {
                    extractorVideo.seekTo( seekPosMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC );
                    seekPosMs = 0;
                }                
                do {
                    int trackIdx = extractorVideo.getSampleTrackIndex();                    
                    Log.v(TAG, "video getSampleTrackIndex: " + trackIdx);
                    if ( trackIdx < 0 ) {
                        isEOS = true;
                        break;                        
                    }
                    long presentationTimeUs = extractorVideo.getSampleTime();
                    if ( videoTrackerIdx == trackIdx ) {
                        if ( vecVideoInIndex.isEmpty() ) {
                            break;
                        }
                        int videoInIdx = vecVideoInIndex.poll();
                        int size = extractorVideo.readSampleData( videoInputBuffers[videoInIdx], 0 );
                        extractorVideo.advance();
                        decoderVideo.queueInputBuffer( videoInIdx, 0, size, presentationTimeUs, 0 );                        
                    }
                } while ( true );         
                
                do {
                    int trackIdx = extractorAudio.getSampleTrackIndex();                    
                    Log.v(TAG, "audio getSampleTrackIndex: " + trackIdx);
                    if ( trackIdx < 0 ) {
                        isEOS = true;
                        break;                        
                    }
                    long presentationTimeUs = extractorAudio.getSampleTime();
                    if ( audioTrackerIdx == trackIdx ) {
                        if ( vecAudioInIndex.isEmpty() ) {
                            break;
                        }
                        int audioInIdx = vecAudioInIndex.poll();
                        int size = extractorAudio.readSampleData( audioInputBuffers[audioInIdx], 0 );
                        extractorAudio.advance();
                        decoderAudio.queueInputBuffer( audioInIdx, 0, size, presentationTimeUs, 0 );                        
                    }
                } while ( true );   
                
                long nowUs = System.nanoTime()/1000;
                if ( 0 == startedUs ) {
                    startedUs = nowUs+1000000l;
                }
                videoTimestampUs = nowUs - startedUs;
                audioTimestampUs = nowUs - startedUs;
                Log.v( TAG, "play both ===========> " + (nowUs-startedUs) );
                while ( !vecAudioOutIndex.isEmpty() ) {
                    int audioOutIdx = vecAudioOutIndex.peek();
                    BufferInfo info = vecAudioInfo.peek();
                    if ( forceShow ) {               
                        audioTimestampUs = info.presentationTimeUs;
                        writeAudioTrack( audioTracker, audioOutputBuffers[audioOutIdx], info.offset, info.size );
                        decoderAudio.releaseOutputBuffer( audioOutIdx, false );
                    } 
                    else {
                        long delayUs = ( nowUs - startedUs - info.presentationTimeUs );
                        Log.v( TAG, "releaseOutputBuffer audio ready: " + delayUs );
                        if ( delayUs > audioDelayMin ) {                            
                            if ( delayUs > audioDelayMax ) {
                                Log.v( TAG, "drop audio" );
                                audioOutIdx = vecAudioOutIndex.poll();
                                info = vecAudioInfo.poll();
                                decoderAudio.releaseOutputBuffer( audioOutIdx, false );
                            }
                            else {
                                Log.v( TAG, "play audio: " + info.presentationTimeUs );
                                audioTimestampUs = info.presentationTimeUs;
                                if ( writeAudioTrack( audioTracker, audioOutputBuffers[audioOutIdx], info.offset, info.size ) ) {
                                    audioOutIdx = vecAudioOutIndex.poll();
                                    info = vecAudioInfo.poll();
                                    decoderAudio.releaseOutputBuffer( audioOutIdx, false );                                    
                                }                                
                            }
                        }
                        else {
                            Log.v( TAG, "too early audio" );
                            break;
                        }                    
                    }
                }
                while ( !vecVideoOutIndex.isEmpty() ) {
                    int videoOutIdx = vecVideoOutIndex.peek();
                    BufferInfo info = vecVideoInfo.peek();
                    if ( forceShow ) {
                        videoTimestampUs = info.presentationTimeUs;
                        decoderVideo.releaseOutputBuffer( videoOutIdx, true );
                    }
                    else {                        
                        long delayUs = ( audioTimestampUs - info.presentationTimeUs );
                        Log.v( TAG, "releaseOutputBuffer video ready: " + delayUs );
                        if ( delayUs > videoDelayMin ) {
                            videoOutIdx = vecVideoOutIndex.poll();
                            info = vecVideoInfo.poll();
                            if ( delayUs > videoDelayMax ) {
                                Log.v( TAG, "drop video" );
                                decoderVideo.releaseOutputBuffer( videoOutIdx, false );
                            }
                            else {
                                Log.v( TAG, "play video: " + info.presentationTimeUs );
                                videoTimestampUs = info.presentationTimeUs;
                                decoderVideo.releaseOutputBuffer( videoOutIdx, true );
                            }
                        }
                        else {
                            Log.v( TAG, "too early video" );
                            break;
                        }
                    }
                }                
                                   
            }
        }
        decoderVideo.stop();
        decoderVideo.release();
        decoderAudio.stop();
        decoderAudio.release();
        extractorVideo.release();
    }

    private long submittedBytes; 
    private int temporaryBufferSize;
    private long lastRawPlaybackHeadPosition;
    private long rawPlaybackHeadWrapCount;
    private long frameSize;
    private int bufferSize;
    private long getPlaybackHeadPosition( AudioTrack audioTracker ) {
        long rawPlaybackHeadPosition = 0xFFFFFFFFL & audioTracker.getPlaybackHeadPosition();
        if (lastRawPlaybackHeadPosition > rawPlaybackHeadPosition) {
          // The value must have wrapped around.
          rawPlaybackHeadWrapCount++;
        }
        lastRawPlaybackHeadPosition = rawPlaybackHeadPosition;
        return rawPlaybackHeadPosition + (rawPlaybackHeadWrapCount << 32);
      }
    private byte[] temporaryBuffer = null;
    private int temporaryBufferOffset = 0;
    private boolean writeAudioTrack(AudioTrack audioTracker, ByteBuffer buf,
            int offset, int size) {
        
        if ( temporaryBufferSize == 0 ) {
            // Copy {@code buffer} into {@code temporaryBuffer}.
            // TODO: Bypass this copy step on versions of Android where [redacted] is implemented.
            if (temporaryBuffer == null || temporaryBuffer.length < size) {
              temporaryBuffer = new byte[size];
            }
            buf.position(offset);
            buf.get(temporaryBuffer, 0, size);
            temporaryBufferOffset = 0;
            temporaryBufferSize = size;
        }
        
        int bytesPending = (int) (submittedBytes - getPlaybackHeadPosition( audioTracker ) * frameSize);
        Log.v( TAG, "bytesPending: " + bytesPending );
        int bytesToWrite = bufferSize - bytesPending;
        Log.v( TAG, "bytesToWrite: " + bytesToWrite );

        if (bytesToWrite > 0) {
          bytesToWrite = Math.min(temporaryBufferSize, bytesToWrite);
          audioTracker.write(temporaryBuffer, temporaryBufferOffset, bytesToWrite);
          temporaryBufferOffset += bytesToWrite;
          temporaryBufferSize -= bytesToWrite;
          submittedBytes += bytesToWrite;
          Log.v( TAG, "temporaryBufferSize: " + temporaryBufferSize );
          if ( 0 == temporaryBufferSize ) {
              return true;
          }
        }
        return false;
    }
    /*
    private void writeAudioTrack(AudioTrack audioTracker, ByteBuffer buf,
            int size) {
        if (null == audioTracker) {
            return;
        }
        final byte[] chunk = new byte[size];
        buf.get(chunk); // Read the buffer all at once
        buf.clear(); // ** MUST DO!!! OTHERWISE THE NEXT TIME YOU GET THIS SAME
                     // BUFFER BAD THINGS WILL HAPPEN
        if (chunk.length > 0) {
            int written = audioTracker.write(chunk, 0, chunk.length);
            audioTracker.flush();
            if ( written != size ) {
                Log.e( TAG, "audioTracker.write NOT completed" );
            }
        }
    }
    */
    
    private static final long MICROS_PER_SECOND = 1000000l;
    private long framesToDurationUs(long frameCount) {
        return (frameCount * MICROS_PER_SECOND) / sampleRate;
      }

      private long durationUsToFrames(long durationUs) {
        return (durationUs * sampleRate) / MICROS_PER_SECOND;
      }
}
