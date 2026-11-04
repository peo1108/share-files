namespace ShareFiles.Server;

/// <summary>Read-only pass-through that counts what it hands out and ends its part of the transfer on dispose.</summary>
internal sealed class TrackingStream(Stream inner, TransferMonitor monitor, TransferEntry entry) : Stream
{
    private bool _disposed;

    public override bool CanRead => true;
    public override bool CanSeek => inner.CanSeek;
    public override bool CanWrite => false;
    public override long Length => inner.Length;

    public override long Position
    {
        get => inner.Position;
        set => inner.Position = value;
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var read = inner.Read(buffer, offset, count);
        entry.AddSent(read);
        return read;
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
    {
        var read = await inner.ReadAsync(buffer, cancellationToken);
        entry.AddSent(read);
        return read;
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken cancellationToken) =>
        ReadAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();

    public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
    public override void Flush() { }
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        // The file result disposes its stream more than once, and the inner file is closed after the first.
        if (disposing && !_disposed)
        {
            _disposed = true;
            monitor.EndStream(entry);
            inner.Dispose();
        }
        base.Dispose(disposing);
    }
}
