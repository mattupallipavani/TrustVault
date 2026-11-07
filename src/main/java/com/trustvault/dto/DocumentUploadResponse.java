package com.trustvault.dto;

public class DocumentUploadResponse {

    private Long id;
    private String fileName;
    private String category;
    private String message;

    public DocumentUploadResponse() {
    }

    public DocumentUploadResponse(Long id, String fileName,
                                  String category, String message) {
        this.id = id;
        this.fileName = fileName;
        this.category = category;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getCategory() {
        return category;
    }

    public String getMessage() {
        return message;
    }
}