package com.todobom.opennotescanner.network.models.dracoon;

public class ChunkUploadResponse {

  private long size;
  private String hash;

  public ChunkUploadResponse(long size, String hash) {
    this.size = size;
    this.hash = hash;
  }

  public long getSize() {
    return size;
  }

  public void setSize(long size) {
    this.size = size;
  }

  public String getHash() {
    return hash;
  }

  public void setHash(String hash) {
    this.hash = hash;
  }
}
