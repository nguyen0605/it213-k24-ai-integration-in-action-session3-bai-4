package com.rikkeiacademy.hr.service;

import com.rikkeiacademy.hr.dto.CandidateExtraction;
import com.rikkeiacademy.hr.entity.Candidate;
import com.rikkeiacademy.hr.repository.CandidateRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class CandidateETLService {

    private final ChatModel chatModel;
    private final CandidateRepository candidateRepository;

    @Autowired
    public CandidateETLService(ChatModel chatModel, CandidateRepository candidateRepository) {
        this.chatModel = chatModel;
        this.candidateRepository = candidateRepository;
    }

    /**
     * Quy trình ETL tối ưu tài nguyên:
     * 1. Extract & Transform (Ngoài Transaction): Gọi LLM API.
     * 2. Validation (Ngoài Transaction): Kiểm tra tính toàn vẹn.
     * 3. Load (Trong Transaction thông qua một proxy bean hoặc phương thức bổ trợ).
     */
    public Candidate processResume(String resumeText) {
        // --- BƯỚC 1: EXTRACT & TRANSFORM (Không nằm trong @Transactional) ---
        CandidateExtraction extraction = extractDataFromResume(resumeText);

        // --- BƯỚC 2: VALIDATION (Kiểm tra nghiệp vụ cơ bản trước khi chiếm giữ connection) ---
        validateCandidateExtraction(extraction);

        // --- BƯỚC 3: LOAD (Mở Transaction để lưu trữ và quản lý đồng bộ DB) ---
        return saveCandidate(extraction);
    }

    private CandidateExtraction extractDataFromResume(String resumeText) {
        BeanOutputConverter<CandidateExtraction> outputConverter = 
                new BeanOutputConverter<>(CandidateExtraction.class);

        String format = outputConverter.getFormat();
        String promptMessage = """
                Bạn là hệ thống AI phân tích CV của Rikkei Academy. 
                Hãy trích xuất thông tin một cách khách quan nhất từ văn bản CV thô dưới đây:
                
                --- BẮT ĐẦU VĂN BẢN CV ---
                {resumeText}
                --- KẾT THÚC VĂN BẢN CV ---
                
                Đảm bảo trả về định dạng theo cấu trúc JSON quy định sau:
                {format}
                """;

        PromptTemplate promptTemplate = new PromptTemplate(promptMessage);
        Prompt prompt = promptTemplate.create(Map.of("resumeText", resumeText, "format", format));

        String response = chatModel.call(prompt).getResult().getOutput().getContent();
        return outputConverter.convert(response);
    }

    private void validateCandidateExtraction(CandidateExtraction extraction) {
        // Nghiệp vụ 1: Kiểm tra họ tên không được để trống hoặc rỗng
        if (extraction.fullName() == null || extraction.fullName().trim().isEmpty()) {
            throw new IllegalArgumentException("Dữ liệu lỗi: Họ tên ứng viên không hợp lệ hoặc rỗng.");
        }

        // Nghiệp vụ 2: Kiểm tra email hợp lệ đơn giản (phải có ký tự @ và .)
        String email = extraction.email();
        if (email == null || !email.contains("@") || !email.contains(".")) {
            throw new IllegalArgumentException("Dữ liệu lỗi: Email không hợp lệ: " + email);
        }

        // Nghiệp vụ 3: Số năm kinh nghiệm phải lớn hơn hoặc bằng 0
        if (extraction.yearsExperience() != null && extraction.yearsExperience() < 0) {
            throw new IllegalArgumentException("Dữ liệu lỗi: Số năm kinh nghiệm không thể âm: " + extraction.yearsExperience());
        }
    }

    /**
     * Ghi nhận cơ sở dữ liệu có tính nguyên tố để không ảnh hưởng Connection Pool lâu dài.
     */
    @Transactional
    public Candidate saveCandidate(CandidateExtraction extraction) {
        // Kiểm tra xem ứng viên đã tồn tại bằng email chưa
        candidateRepository.findByEmail(extraction.email()).ifPresent(existing -> {
            throw new IllegalStateException("Ứng viên với địa chỉ email " + extraction.email() + " đã tồn tại.");
        });

        Candidate candidate = new Candidate(
                extraction.fullName(),
                extraction.phone(),
                extraction.email(),
                extraction.skills(),
                extraction.yearsExperience()
        );

        return candidateRepository.save(candidate);
    }
}