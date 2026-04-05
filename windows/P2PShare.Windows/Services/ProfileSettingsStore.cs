using System.Text.Json;

namespace ShareVia.Windows.Services;

public sealed class ProfileSettingsStore
{
    private readonly string _settingsPath;
    private readonly JsonSerializerOptions _jsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    public ProfileSettingsStore()
    {
        var root =
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "ShareViaNative"
            );
        Directory.CreateDirectory(root);
        _settingsPath = Path.Combine(root, "profile.json");
    }

    public ProfileSettings Load()
    {
        if (!File.Exists(_settingsPath))
        {
            return new ProfileSettings(Environment.MachineName, null);
        }

        try
        {
            var json = File.ReadAllText(_settingsPath);
            return JsonSerializer.Deserialize<ProfileSettings>(json, _jsonOptions)
                   ?? new ProfileSettings(Environment.MachineName, null);
        }
        catch
        {
            return new ProfileSettings(Environment.MachineName, null);
        }
    }

    public void Save(ProfileSettings settings)
    {
        var cleanName =
            string.IsNullOrWhiteSpace(settings.DisplayName)
                ? Environment.MachineName
                : settings.DisplayName.Trim();
        var payload = settings with { DisplayName = cleanName };
        var json = JsonSerializer.Serialize(payload, _jsonOptions);
        File.WriteAllText(_settingsPath, json);
    }
}

public sealed record ProfileSettings(string DisplayName, string? AvatarPath);
