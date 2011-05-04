package org.zakky.mokumoku.server;

public final class ClientStatus {
    private final int mId;
    
    private final byte[] mPayload;

    public static final int INVALID_ID = 0;
    
    public ClientStatus() {
        mId = INVALID_ID;
        mPayload = new byte[0];
    }
    
    public ClientStatus(int mId, byte[] data, int offset, int length) {
        super();
        this.mId = mId;
        if (mId == INVALID_ID) {
            // TODO 例外をちゃんとる
            throw new RuntimeException("client id (" + INVALID_ID + ") is not valid.");
        }
        
        // TODO offset と length の正当性のチェック
        
        mPayload = new byte[length];
        System.arraycopy(data, offset, mPayload, 0, length);
    }

    public int getId() {
        return mId;
    }

    public byte[] getPayload() {
        return mPayload;
    }
}
