package faang.school.postservice.dto;


import lombok.Data;

@Data
public class LikeDto {
    private long id;
    private Long userId;
    private Long commentId;
    private Long postId;
}
