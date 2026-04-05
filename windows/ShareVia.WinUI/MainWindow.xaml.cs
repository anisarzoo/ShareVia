using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace ShareVia.WinUI;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    private void RootNav_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItemContainer is not NavigationViewItem item)
        {
            return;
        }

        var tag = item.Tag?.ToString() ?? "home";
        SectionTitle.Text = tag switch
        {
            "home" => "Home",
            "devices" => "Devices",
            "profile" => "Profile",
            "history" => "History",
            "settings" => "Settings",
            "ecosystem" => "Ecosystem",
            "diagnostics" => "Diagnostics",
            "tools" => "Optional Tools",
            _ => "Home",
        };
    }
}
