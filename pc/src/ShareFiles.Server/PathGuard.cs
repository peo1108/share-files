namespace ShareFiles.Server;

/// <summary>Maps a client path onto a shared folder and refuses anything that could leave it.</summary>
public static class PathGuard
{
    /// <summary>Returns the full path of an existing entry. Throws 403 for unsafe paths and 404 for missing ones.</summary>
    public static string Resolve(string rootPath, string? relativePath)
    {
        var current = Path.GetFullPath(rootPath);
        if (!Directory.Exists(current)) throw ApiException.NotFound();
        if (string.IsNullOrEmpty(relativePath)) return current;

        if (relativePath.IndexOfAny(['\\', ':', '\0']) >= 0 || relativePath[0] == '/')
        {
            throw ApiException.PathForbidden();
        }

        var segments = relativePath.Split('/');

        // Windows silently strips trailing dots and spaces, so "..." or ".. " must not get through.
        if (segments.Any(s => s.Length == 0 || s.EndsWith('.') || s.EndsWith(' ') || FileNames.IsReserved(s)))
        {
            throw ApiException.PathForbidden();
        }

        foreach (var segment in segments)
        {
            current = Path.Combine(current, segment);
            FileAttributes attributes;
            try
            {
                attributes = File.GetAttributes(current);
            }
            catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException)
            {
                throw ApiException.NotFound();
            }
            catch (UnauthorizedAccessException)
            {
                throw ApiException.PathForbidden();
            }

            if (attributes.HasFlag(FileAttributes.ReparsePoint)) throw ApiException.PathForbidden();
        }
        return current;
    }
}
