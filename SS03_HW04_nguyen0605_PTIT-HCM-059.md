# BÁI TẬP 4: SÁNG TẠO — MODULE ETL RESUME PARSER (RIKKEI ACADEMY HR)

## PHẦN 1: TÓM TẮT YÊU CẦU ĐỀ BÀI
Bộ phận HR của Rikkei Academy hàng ngày phải xử lý hàng trăm CV thô phi cấu trúc từ ứng viên. Để tự động hóa và nâng cao hiệu suất, chúng ta cần phát triển hệ thống **ETL Resume Parser** ứng dụng Trí tuệ Nhân tạo (AI):
- **Extract (Trích xuất):** Nhận diện CV thô từ văn bản thô.
- **Transform (Chuyển đổi):** Sử dụng `ChatModel` kết hợp `BeanOutputConverter` từ Spring AI để phân tích cú pháp và bóc tách dữ liệu thô sang Java Record `CandidateExtraction` có cấu trúc rõ ràng.
- **Load (Tải dữ liệu):** Thực hiện kiểm định (Validation) ít nhất 02 nghiệp vụ (Họ tên không rỗng, Email đúng cú pháp, Số năm kinh nghiệm không âm) trước khi ghi dữ liệu xuống cơ sở dữ liệu quan hệ (H2/MySQL) thông qua Spring Data JPA.
- **Phân tích kiến trúc:** Thiết lập sơ đồ luồng dữ liệu ASCII chi tiết và đánh giá ưu/nhược điểm (trade-off) của việc đặt lệnh gọi LLM API ngoài hay trong ranh giới `@Transactional` liên quan đến Connection Pool và Rollback.

---

## PHẦN 2: GIẢ LẬP CUỘC TRÒ CHUYỆN THỰC TẾ VỚI AI

### 1. Câu lệnh Prompt gửi cho LLM
```text
[System Prompt]
Bạn là một AI Parser chuyên nghiệp có nhiệm vụ trích xuất thông tin từ CV ứng viên theo định dạng JSON được yêu cầu.

[User Prompt]
Hãy trích xuất dữ liệu từ văn bản CV thô sau đây:
---
NGUYỄN VĂN AN
SĐT: 0987.654.321
Email: an.nguyenvan@gmail.com
Kinh nghiệm làm việc:
- 3 năm làm lập trình viên Java Web tại RikkeiSoft.
- Phát triển các dịch vụ RESTful API sử dụng Spring Boot, JPA, Hibernate.
- Làm việc với cơ sở dữ liệu MySQL và Redis.
- Kỹ năng chính: Java, Spring Boot, MySQL, Docker, Git.
---

Định dạng đầu ra bắt buộc phải khớp với cấu trúc JSON Schema sau đây:
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "fullName": { "type": "string" },
    "phone": { "type": "string" },
    "email": { "type": "string" },
    "skills": {
      "type": "array",
      "items": { "type": "string" }
    },
    "yearsExperience": { "type": "integer" }
  },
  "required": ["fullName", "phone", "email", "skills", "yearsExperience"]
}
```

### 2. Phản hồi từ AI (JSON Trả Về)
```json
{
  "fullName": "Nguyễn Văn An",
  "phone": "0987.654.321",
  "email": "an.nguyenvan@gmail.com",
  "skills": ["Java", "Spring Boot", "MySQL", "Docker", "Git"],
  "yearsExperience": 3
}
```

---

## PHẦN 3: SƠ ĐỒ ASCII MÔ TẢ LUỒNG DỮ LIỆU ETL

```text
                               +-------------------------+
                               |      CV Thô (Text)      |
                               +------------+------------+
                                            | 
                                            | (1) Nhập vào processResume(resumeText)
                                            v
+-----------------------+      (2) Gửi Prompt + Schema       +-----------------------+
|     Spring AI LLM     | =================================> |     OpenAI Engine     |
|    (ChatModel &       |                                    |    (External API)     |
|  BeanOutputConverter) | <================================= | (Xử lý lâu: 1 - 5 sec)|
+-----------+-----------+        (3) Trả về JSON chuần       +-----------------------+
            |
            | (4) Map & Convert thành Record CandidateExtraction
            v
+-----------------------+
|   Validation Engine   | ----> Kiểm tra 1: fullName không được để trống / null
|   (Business Rules)    | ----> Kiểm tra 2: email phải chứa kí tự '@' và '.'
+-----------+-----------+ ----> Kiểm tra 3: yearsExperience không được phép < 0
            |
            | (5) Đạt chuẩn -> Chuyển sang saveCandidate(extraction)
            v
+-----------------------+
|      Repository       | -------------------------> [ Bắt đầu @Transactional ]
| (CandidateRepository) |                            [ Tìm kiếm trùng lặp Email ]
+-----------+-----------+                            [ Thực thi lệnh INSERT SQL ]
            |                                        [ Commit & Trả tài nguyên ]
            v
+-----------------------+
|       Database        |
|      (SQL Store)      |
+-----------------------+
```

---

## PHẦN 4: BÀI PHÂN TÍCH CHUYÊN SÂU TRÊN KHÍA CẠNH KỸ THUẬT (TRADE-OFFS)
Việc kết hợp giữa **lệnh gọi mạng chậm (I/O Network Call tới LLM API)** và **Giao dịch Cơ sở dữ liệu (@Transactional)** đòi hỏi sự cân nhắc thiết kế kỹ lưỡng để tối ưu hóa tài nguyên hệ thống.

### Phương án 1: Đặt LLM Call BÊN TRONG phương thức `@Transactional`
```java
@Transactional
public Candidate processResume(String resumeText) {
    CandidateExtraction extraction = chatModel.call(...); // LLM Call nằm trong Transaction
    validate(extraction);
    return candidateRepository.save(new Candidate(extraction));
}
```
*   **Ưu điểm:**
    *   **Tính nguyên tố (Atomicity) đơn giản:** Toàn bộ quá trình từ khi phân tích tới khi ghi DB được đóng gói trong một Transaction duy nhất. Nếu có bất kỳ lỗi Runtime nào xảy ra ở bất kỳ bước nào (kể cả lỗi lưu DB), trạng thái sẽ tự động rollback.
    *   **Dễ triển khai:** Chỉ cần gán một Annotation `@Transactional` duy nhất tại Service Method.
*   **Nhược điểm (CỰC KỲ NGUY HIỂM):**
    *   **Chiếm dụng Connection Pool lâu dài (Database Connection Starvation):** Khi bước vào phương thức `@Transactional`, Spring sẽ lấy một Connection từ HikariCP Connection Pool và bắt đầu transaction. Cuộc gọi API đến LLM thường mất khoảng **1-5 giây** (hoặc hơn tùy thuộc độ trễ mạng và độ tải của OpenAI). Trong suốt thời gian này, Connection đó bị giữ lại (chờ I/O không hoạt động) mà không làm gì cả. Nếu có nhiều request đồng thời, Pool sẽ nhanh chóng bị cạn kiệt, dẫn đến treo toàn bộ hệ thống (`SQLTransientConnectionException: Connection is not available`).
    *   **Không thể Rollback giao dịch bên thứ ba:** Nếu database bị lỗi và thực hiện Rollback, tiền phí API LLM đã mất không thể hoàn lại, cuộc gọi LLM không thể phục hồi.

### Phương án 2: Đặt LLM Call BÊN NGOÀI phương thức `@Transactional` (Khuyên Dùng)
```java
public Candidate processResume(String resumeText) {
    CandidateExtraction extraction = chatModel.call(...); // LLM Call NẰM NGOÀI Transaction
    validate(extraction);
    return saveCandidate(extraction); // Gọi phương thức có @Transactional để ghi DB
}

@Transactional
public Candidate saveCandidate(CandidateExtraction extraction) {
    return candidateRepository.save(new Candidate(extraction));
}
```
*   **Ưu điểm:**
    *   **Tối ưu hóa tài nguyên vượt trội:** Connection từ Database Pool chỉ được mở ra ở giây phút cuối cùng khi thực hiện thao tác lưu vào Cơ sở dữ liệu (`saveCandidate`). Thời gian chiếm dụng connection giảm xuống chỉ còn khoảng vài mili-giây thay vì hàng giây chờ đợi API LLM bên thứ ba.
    *   **Tăng khả năng chịu tải (High Throughput):** Hệ thống có thể xử lý hàng trăm luồng song song mà không sợ nghẽn cổ chai tại Connection Pool.
*   **Nhược điểm:**
    *   **Phức tạp trong chia tách:** Đòi hỏi lập trình viên phải chia mã nguồn thành các lớp hoặc phương thức riêng biệt, chú ý đến cơ chế tự gọi (self-invocation) của Spring AOP (phải gọi thông qua proxy của Bean khác hoặc tách lớp/chia cấu trúc chuẩn xác như mã nguồn bên dưới).

**Kết luận:** Phương án 2 là kiến trúc tối ưu nhất cho môi trường sản xuất thực tế (Production-ready).