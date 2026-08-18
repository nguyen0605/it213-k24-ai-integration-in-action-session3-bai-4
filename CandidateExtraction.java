package com.rikkeiacademy.hr.dto;

import java.util.List;

/**
 * Java Record đóng vai trò là DTO lưu trữ dữ liệu thô đã qua cấu trúc từ LLM.
 */
public record CandidateExtraction(
    String fullName,
    String phone,
    String email,
    List<String> skills,
    Integer yearsExperience
) {}