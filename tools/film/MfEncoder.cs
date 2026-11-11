// Assemble des PNG numérotés en MP4 H.264 par l'encodeur de Windows, sans ffmpeg, et relit le résultat.
//   MfEncoder.exe <dossier des f*.png> <sortie.mp4> <fps> <largeur> <hauteur> <débit en bit/s>
//   MfEncoder.exe --verifier <film.mp4> <dossier de sortie> <secondes...>
// Les chemins doivent être absolus. tools/film/encoder.py le compile et l'appelle.
using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Windows.Foundation;
using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using Windows.Storage;

public static class MfEncoder {
  public static string Verifier(string mp4, string dossier, double[] secondes) {
    var f = Wait(StorageFile.GetFileFromPathAsync(mp4));
    var clip = Wait(MediaClip.CreateFromFileAsync(f));
    var comp = new MediaComposition();
    comp.Clips.Add(clip);
    var p = clip.GetVideoEncodingProperties();
    string r = "duree=" + clip.OriginalDuration + " taille=" + p.Width + "x" + p.Height + " sujet=" + p.Subtype + " debit=" + p.Bitrate;
    Directory.CreateDirectory(dossier);
    foreach (var s in secondes) {
      var st = Wait(comp.GetThumbnailAsync(TimeSpan.FromSeconds(s), (int)p.Width, (int)p.Height, VideoFramePrecision.NearestFrame));
      using (var ins = System.IO.WindowsRuntimeStreamExtensions.AsStreamForRead(st))
      using (var o = File.Create(Path.Combine(dossier, "t" + ((int)(s * 10)).ToString("D5") + ".jpg"))) { ins.CopyTo(o); }
    }
    return r;
  }

  public static int Main(string[] a) {
    try {
      if (a[0] == "--verifier") {
        var t = new System.Collections.Generic.List<double>();
        for (int i = 3; i < a.Length; i++) t.Add(double.Parse(a[i], System.Globalization.CultureInfo.InvariantCulture));
        Console.WriteLine(Verifier(a[1], a[2], t.ToArray()));
        return 0;
      }
      Console.WriteLine(Run(a[0], a[1], double.Parse(a[2], System.Globalization.CultureInfo.InvariantCulture), int.Parse(a[3]), int.Parse(a[4]), int.Parse(a[5])));
      return 0;
    } catch (Exception e) { Console.Error.WriteLine(e); return 1; }
  }

  static T Wait<T>(IAsyncOperation<T> op) {
    var t = System.WindowsRuntimeSystemExtensions.AsTask(op);
    t.Wait();
    return t.Result;
  }

  public static string Run(string frames, string output, double fps, int width, int height, int bitrate) {
    var files = Directory.GetFiles(frames, "f*.png").OrderBy(f => f, StringComparer.Ordinal).ToArray();
    if (files.Length == 0) throw new Exception("aucune image dans " + frames);
    // FromSeconds arrondit à la milliseconde : 17 ms par image au lieu de 16,67.
    var dur = TimeSpan.FromTicks((long)Math.Round(TimeSpan.TicksPerSecond / fps));
    var comp = new MediaComposition();
    foreach (var f in files) {
      var sf = Wait(StorageFile.GetFileFromPathAsync(f));
      comp.Clips.Add(Wait(MediaClip.CreateFromImageFileAsync(sf, dur)));
    }
    var prof = MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD720p);
    prof.Video.Width = (uint)width;
    prof.Video.Height = (uint)height;
    prof.Video.Bitrate = (uint)bitrate;
    prof.Video.FrameRate.Numerator = (uint)Math.Round(fps);
    prof.Video.FrameRate.Denominator = 1;
    prof.Audio = null;
    var dir = Path.GetDirectoryName(output);
    Directory.CreateDirectory(dir);
    if (File.Exists(output)) File.Delete(output);
    var folder = Wait(StorageFolder.GetFolderFromPathAsync(dir));
    var dest = Wait(folder.CreateFileAsync(Path.GetFileName(output), CreationCollisionOption.ReplaceExisting));
    var task = System.WindowsRuntimeSystemExtensions.AsTask(comp.RenderToFileAsync(dest, MediaTrimmingPreference.Fast, prof));
    task.Wait();
    return "clips=" + files.Length + " duree=" + comp.Duration + " resultat=" + task.Result;
  }
}
