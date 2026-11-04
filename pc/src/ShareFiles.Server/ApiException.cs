namespace ShareFiles.Server;

/// <summary>An error the client is meant to see, in the shape defined by protocol/PROTOCOL.md.</summary>
public sealed class ApiException(int status, string code, string message, long? offset = null) : Exception(message)
{
    public int Status { get; } = status;
    public string Code { get; } = code;
    public long? Offset { get; } = offset;

    public static ApiException BadRequest(string message) => new(400, "bad_request", message);
    public static ApiException NotFound(string message = "Không tìm thấy") => new(404, "not_found", message);
    public static ApiException PathForbidden() => new(403, "path_forbidden", "Đường dẫn nằm ngoài thư mục được chia sẻ");
    public static ApiException InsufficientStorage() => new(507, "insufficient_storage", "PC đã hết dung lượng");
}
