using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using RevitVisualizer.Core;

namespace RevitVisualizer.Windows;

public sealed class CredentialDialog : Window
{
    private readonly PasswordBox entry = new() { MaxLength = 4096, Margin = new Thickness(0, 12, 0, 12) };
    private readonly CheckBox remember = new() { Content = "Remember on this PC", Margin = new Thickness(0, 0, 0, 8) };
    private readonly TextBlock validation = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
    public char[]? Credential { get; private set; }
    public bool Remember => remember.IsChecked == true;
    public CredentialDialog()
    {
        Style = (Style)FindResource(typeof(Window));
        Title = "OpenAI API key"; Width = 480; SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock { Text = "OpenAI API key", FontSize = 22, FontWeight = FontWeights.SemiBold });
        panel.Children.Add(new TextBlock { Text = "For personal use. Enter your own key here; it is sent only to OpenAI.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 0) });
        entry.SetValue(System.Windows.Automation.AutomationProperties.NameProperty, "API key");
        panel.Children.Add(entry); panel.Children.Add(remember);
        panel.Children.Add(new TextBlock { Text = "Remembered keys are encrypted for your Windows account. Clear key removes the saved copy. Otherwise the key stays in memory for this session.", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(new TextBlock { Text = "Visualize sends the prepared image to OpenAI and uses your API credits.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 0) });
        panel.Children.Add(validation);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var cancel = new Button { Content = "Cancel", IsCancel = true };
        var submit = new Button { Content = "Visualize", IsDefault = true, Style = (Style)FindResource("Primary") };
        submit.Click += (_, _) =>
        {
            using var secure = entry.SecurePassword;
            nint memory = Marshal.SecureStringToGlobalAllocUnicode(secure);
            char[] value = new char[secure.Length];
            try { Marshal.Copy(memory, value, 0, value.Length); }
            finally { Marshal.ZeroFreeGlobalAllocUnicode(memory); }
            if (!OpenAiImagesClient.IsValidCredential(value)) { Array.Clear(value); validation.Text = "Enter a valid API key without spaces or line breaks."; return; }
            Credential = value; entry.Clear(); DialogResult = true;
        };
        actions.Children.Add(cancel); actions.Children.Add(submit); panel.Children.Add(actions);
        Content = panel;
        Loaded += (_, _) => { NativePrivacy.ExcludeFromCapture(this); entry.Focus(); };
        Closed += (_, _) => entry.Clear();
    }
}

internal static class NativePrivacy
{
    [DllImport("user32.dll")] private static extern bool SetWindowDisplayAffinity(nint window, uint affinity);
    public static void ExcludeFromCapture(Window window) => SetWindowDisplayAffinity(new System.Windows.Interop.WindowInteropHelper(window).Handle, 0x11);
}
