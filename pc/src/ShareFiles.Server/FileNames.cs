using System.Text;
using System.Text.RegularExpressions;

namespace ShareFiles.Server;

public static partial class FileNames
{
    private const int MaxLength = 200;

    /// <summary>Turns a name supplied by a client into a safe single file name for Windows.</summary>
    public static string Sanitize(string? name)
    {
        var value = (name ?? "").Normalize(NormalizationForm.FormC);

        var lastSeparator = value.LastIndexOfAny(['/', '\\']);
        if (lastSeparator >= 0) value = value[(lastSeparator + 1)..];

        var builder = new StringBuilder(value.Length);
        foreach (var c in value)
        {
            builder.Append(c < 32 || "<>:\"|?*".Contains(c) ? '_' : c);
        }
        value = builder.ToString().Trim().TrimEnd('.', ' ');

        if (value.Length == 0) return "file";
        if (IsReserved(value)) value = "_" + value;
        return Truncate(value);
    }

    /// <summary>True for names Windows maps to devices, with or without an extension.</summary>
    public static bool IsReserved(string name) => ReservedName().IsMatch(name);

    /// <summary>Moves <paramref name="source"/> into a directory without overwriting, adding " (1)" on clashes.</summary>
    public static string MoveToUniqueName(string source, string directory, string name)
    {
        Directory.CreateDirectory(directory);
        var stem = Path.GetFileNameWithoutExtension(name);
        var extension = Path.GetExtension(name);
        for (var attempt = 0; ; attempt++)
        {
            var candidate = attempt == 0 ? name : $"{stem} ({attempt}){extension}";
            var target = Path.Combine(directory, candidate);
            if (File.Exists(target) || Directory.Exists(target)) continue;
            try
            {
                File.Move(source, target, overwrite: false);
                return candidate;
            }
            catch (IOException) when (File.Exists(target))
            {
                // Another upload took the name between the check and the move.
            }
        }
    }

    private static string Truncate(string value)
    {
        if (value.Length <= MaxLength) return value;
        var extension = Path.GetExtension(value);
        if (extension.Length >= MaxLength) extension = "";
        return value[..(MaxLength - extension.Length)] + extension;
    }

    [GeneratedRegex(@"^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\..*)?$", RegexOptions.IgnoreCase)]
    private static partial Regex ReservedName();
}
