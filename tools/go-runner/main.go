package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"image"
	"image/png"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	maa "github.com/MaaXYZ/maa-framework-go/v4"
)

type runResult struct {
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
	Entry   string `json:"entry,omitempty"`
}

type shellController struct {
	mu        sync.Mutex
	connected bool
	width     int32
	height    int32
	touches   map[int32]touchPoint
}

type touchPoint struct {
	x int32
	y int32
}

func newShellController() *shellController {
	return &shellController{
		touches: make(map[int32]touchPoint),
	}
}

func (c *shellController) Connect() bool {
	width, height, err := queryDisplaySize()
	if err == nil {
		c.mu.Lock()
		c.width = width
		c.height = height
		c.connected = true
		c.mu.Unlock()
		return true
	}

	img, ok := c.Screencap()
	if !ok {
		return false
	}
	bounds := img.Bounds()
	c.mu.Lock()
	c.width = int32(bounds.Dx())
	c.height = int32(bounds.Dy())
	c.connected = true
	c.mu.Unlock()
	return true
}

func (c *shellController) Connected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.connected
}

func (c *shellController) RequestUUID() (string, bool) {
	return "android-root-controller", true
}

func (c *shellController) GetFeature() maa.ControllerFeature {
	return maa.ControllerFeatureNone
}

func (c *shellController) StartApp(intent string) bool {
	if intent == "" {
		return true
	}
	if strings.Contains(intent, "/") {
		return runCommand(context.Background(), "/system/bin/am", "start", "-n", intent) == nil
	}
	return runCommand(context.Background(), "/system/bin/monkey", "-p", intent, "-c", "android.intent.category.LAUNCHER", "1") == nil
}

func (c *shellController) StopApp(intent string) bool {
	if intent == "" {
		return true
	}
	pkg := intent
	if slash := strings.Index(pkg, "/"); slash >= 0 {
		pkg = pkg[:slash]
	}
	return runCommand(context.Background(), "/system/bin/am", "force-stop", pkg) == nil
}

func (c *shellController) Screencap() (image.Image, bool) {
	output, err := exec.Command("/system/bin/screencap", "-p").Output()
	if err != nil {
		return nil, false
	}
	output = bytes.ReplaceAll(output, []byte("\r\n"), []byte("\n"))
	img, err := png.Decode(bytes.NewReader(output))
	if err != nil {
		return nil, false
	}
	bounds := img.Bounds()
	c.mu.Lock()
	c.width = int32(bounds.Dx())
	c.height = int32(bounds.Dy())
	c.mu.Unlock()
	return img, true
}

func (c *shellController) Click(x, y int32) bool {
	return runInputTouch("tap", strconv.Itoa(int(x)), strconv.Itoa(int(y))) == nil
}

func (c *shellController) Swipe(x1, y1, x2, y2, duration int32) bool {
	return runInputTouch(
		"swipe",
		strconv.Itoa(int(x1)),
		strconv.Itoa(int(y1)),
		strconv.Itoa(int(x2)),
		strconv.Itoa(int(y2)),
		strconv.Itoa(int(duration)),
	) == nil
}

func (c *shellController) TouchDown(contact, x, y, pressure int32) bool {
	c.mu.Lock()
	c.touches[contact] = touchPoint{x: x, y: y}
	c.mu.Unlock()
	return runInputTouch("motionevent", "DOWN", strconv.Itoa(int(x)), strconv.Itoa(int(y))) == nil
}

func (c *shellController) TouchMove(contact, x, y, pressure int32) bool {
	c.mu.Lock()
	c.touches[contact] = touchPoint{x: x, y: y}
	c.mu.Unlock()
	return runInputTouch("motionevent", "MOVE", strconv.Itoa(int(x)), strconv.Itoa(int(y))) == nil
}

func (c *shellController) TouchUp(contact int32) bool {
	c.mu.Lock()
	point, ok := c.touches[contact]
	delete(c.touches, contact)
	c.mu.Unlock()
	if !ok {
		point = touchPoint{x: 0, y: 0}
	}
	return runInputTouch("motionevent", "UP", strconv.Itoa(int(point.x)), strconv.Itoa(int(point.y))) == nil
}

func (c *shellController) ClickKey(keycode int32) bool {
	return runInputKeyboard("keyevent", strconv.Itoa(int(keycode))) == nil
}

func (c *shellController) InputText(text string) bool {
	text = strings.ReplaceAll(text, " ", "%s")
	return runInputKeyboard("text", text) == nil
}

func (c *shellController) KeyDown(keycode int32) bool {
	return runInputKeyboard("keyevent", "--duration", "400", strconv.Itoa(int(keycode))) == nil
}

func (c *shellController) KeyUp(keycode int32) bool {
	return true
}

func (c *shellController) Scroll(dx, dy int32) bool {
	return runInputTouch(
		"scroll",
		"0",
		"0",
		"--axis",
		fmt.Sprintf("VSCROLL,%d", dy),
		"--axis",
		fmt.Sprintf("HSCROLL,%d", dx),
	) == nil
}

func (c *shellController) Inactive() bool {
	return true
}

func (c *shellController) GetInfo() (string, bool) {
	return `{"type":"adb","name":"ADB"}`, true
}

func main() {
	result, exitCode := run()
	_ = json.NewEncoder(os.Stdout).Encode(result)
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}

func run() (runResult, int) {
	runtimeRoot := flag.String("runtime-root", "", "Runtime root directory")
	entry := flag.String("entry", "", "Pipeline entry to execute")
	override := flag.String("override", "{}", "Pipeline override JSON")
	resourceName := flag.String("resource-name", "官服", "Selected resource name")
	controllerName := flag.String("controller-name", "ADB", "Selected controller name")
	controllerType := flag.String("controller-type", "Adb", "Selected controller type")
	clientName := flag.String("client-name", "MaaFrameworkAndroid", "PI client name")
	clientVersion := flag.String("client-version", "0.1.0-mvp", "PI client version")
	clientLanguage := flag.String("client-language", "zh_cn", "PI client language")
	projectVersion := flag.String("project-version", "v0.1.0", "PI project version")
	flag.Parse()

	result := runResult{
		Success: false,
		Entry:   *entry,
	}

	if *runtimeRoot == "" || *entry == "" {
		result.Message = "missing required args: --runtime-root and --entry"
		return result, 2
	}

	libDir := filepath.Join(*runtimeRoot, "maafw")
	logDir := filepath.Join(*runtimeRoot, "logs")
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		result.Message = fmt.Sprintf("failed to create log dir: %v", err)
		return result, 1
	}

	if err := maa.Init(
		maa.WithLibDir(libDir),
		maa.WithLogDir(logDir),
		maa.WithStdoutLevel(maa.LoggingLevelInfo),
	); err != nil {
		result.Message = fmt.Sprintf("failed to initialize MaaFramework: %v", err)
		return result, 1
	}
	defer maa.Release()

	if err := maa.ConfigInitOption(*runtimeRoot, "{}"); err != nil {
		result.Message = fmt.Sprintf("failed to init MaaToolkit config: %v", err)
		return result, 1
	}

	tasker, err := maa.NewTasker()
	if err != nil {
		result.Message = fmt.Sprintf("failed to create tasker: %v", err)
		return result, 1
	}
	defer tasker.Destroy()

	ctrl, err := maa.NewCustomController(newShellController())
	if err != nil {
		result.Message = fmt.Sprintf("failed to create custom controller: %v", err)
		return result, 1
	}
	defer ctrl.Destroy()
	if !ctrl.PostConnect().Wait().Success() {
		result.Message = "failed to connect custom controller"
		return result, 1
	}
	if err := tasker.BindController(ctrl); err != nil {
		result.Message = fmt.Sprintf("failed to bind controller: %v", err)
		return result, 1
	}

	res, err := maa.NewResource()
	if err != nil {
		result.Message = fmt.Sprintf("failed to create resource: %v", err)
		return result, 1
	}
	defer res.Destroy()

	if !res.PostBundle(filepath.Join(*runtimeRoot, "resource")).Wait().Success() {
		result.Message = "failed to load resource bundle"
		return result, 1
	}
	resourceAdbDir := filepath.Join(*runtimeRoot, "resource_adb")
	if stat, err := os.Stat(resourceAdbDir); err == nil && stat.IsDir() {
		if !res.PostBundle(resourceAdbDir).Wait().Success() {
			result.Message = "failed to load resource_adb bundle"
			return result, 1
		}
	}
	if err := tasker.BindResource(res); err != nil {
		result.Message = fmt.Sprintf("failed to bind resource: %v", err)
		return result, 1
	}
	if !tasker.Initialized() {
		result.Message = "tasker initialization failed"
		return result, 1
	}

	client, err := maa.NewAgentClient()
	if err != nil {
		result.Message = fmt.Sprintf("failed to create agent client: %v", err)
		return result, 1
	}
	defer client.Destroy()
	if err := client.BindResource(res); err != nil {
		result.Message = fmt.Sprintf("failed to bind resource to agent client: %v", err)
		return result, 1
	}

	identifier, err := client.Identifier()
	if err != nil {
		result.Message = fmt.Sprintf("failed to get agent client identifier: %v", err)
		return result, 1
	}

	child, cleanup, err := startGoService(
		*runtimeRoot,
		identifier,
		piEnv(*runtimeRoot, *resourceName, *controllerName, *controllerType, *clientName, *clientVersion, *clientLanguage, *projectVersion),
	)
	if err != nil {
		result.Message = fmt.Sprintf("failed to start go-service: %v", err)
		return result, 1
	}
	defer cleanup()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		_ = client.Disconnect()
		if child.Process != nil {
			_ = child.Process.Kill()
		}
	}()

	if err := client.SetTimeout(20 * time.Second); err != nil {
		result.Message = fmt.Sprintf("failed to set agent timeout: %v", err)
		return result, 1
	}
	if err := client.Connect(); err != nil {
		result.Message = fmt.Sprintf("failed to connect agent client: %v", err)
		return result, 1
	}
	defer client.Disconnect()

	taskJob := tasker.PostTask(*entry, *override).Wait()
	if err := taskJob.Error(); err != nil {
		result.Message = fmt.Sprintf("task job error: %v", err)
		return result, 1
	}

	detail, err := taskJob.GetDetail()
	if err != nil {
		result.Message = fmt.Sprintf("failed to get task detail: %v", err)
		return result, 1
	}
	if !taskJob.Success() {
		result.Message = fmt.Sprintf("task failed with status=%v entry=%s", detail.Status, detail.Entry)
		return result, 1
	}

	result.Success = true
	result.Message = "task completed"
	return result, 0
}

func startGoService(runtimeRoot string, identifier string, extraEnv []string) (*exec.Cmd, func(), error) {
	goServicePath := filepath.Join(runtimeRoot, "agent", "go-service")
	logPath := filepath.Join(runtimeRoot, "logs", "go-service.log")
	logFile, err := os.Create(logPath)
	if err != nil {
		return nil, nil, err
	}

	cmd := exec.Command(goServicePath, identifier)
	cmd.Dir = runtimeRoot
	cmd.Env = append(os.Environ(), extraEnv...)
	cmd.Stdout = logFile
	cmd.Stderr = logFile

	if err := cmd.Start(); err != nil {
		logFile.Close()
		return nil, nil, err
	}

	cleanup := func() {
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
			_, _ = cmd.Process.Wait()
		}
		_ = logFile.Close()
	}

	return cmd, cleanup, nil
}

func piEnv(runtimeRoot, resourceName, controllerName, controllerType, clientName, clientVersion, clientLanguage, projectVersion string) []string {
	controllerJSON := map[string]any{
		"name":                 controllerName,
		"label":                controllerName,
		"type":                 controllerType,
		"attach_resource_path": []string{"./resource_adb"},
	}
	resourceJSON := map[string]any{
		"name":  resourceName,
		"label": resourceName,
		"path":  []string{"./resource"},
	}
	controllerData, _ := json.Marshal(controllerJSON)
	resourceData, _ := json.Marshal(resourceJSON)
	return []string{
		"LD_LIBRARY_PATH=" + filepath.Join(runtimeRoot, "maafw"),
		"PI_INTERFACE_VERSION=v2.5.0",
		"PI_CLIENT_NAME=" + clientName,
		"PI_CLIENT_VERSION=" + clientVersion,
		"PI_CLIENT_LANGUAGE=" + clientLanguage,
		"PI_CLIENT_MAAFW_VERSION=v5.10.2",
		"PI_VERSION=" + projectVersion,
		"PI_CONTROLLER=" + string(controllerData),
		"PI_RESOURCE=" + string(resourceData),
	}
}

func runInputTouch(args ...string) error {
	allArgs := append([]string{"touchscreen"}, args...)
	return runCommand(context.Background(), "/system/bin/input", allArgs...)
}

func runInputKeyboard(args ...string) error {
	allArgs := append([]string{"keyboard"}, args...)
	return runCommand(context.Background(), "/system/bin/input", allArgs...)
}

func runCommand(ctx context.Context, bin string, args ...string) error {
	cmd := exec.CommandContext(ctx, bin, args...)
	output, err := cmd.CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(output))
		if message == "" {
			return err
		}
		return fmt.Errorf("%w: %s", err, message)
	}
	return nil
}

func queryDisplaySize() (int32, int32, error) {
	output, err := exec.Command("/system/bin/wm", "size").CombinedOutput()
	if err != nil {
		return 0, 0, err
	}
	re := regexp.MustCompile(`(\d+)x(\d+)`)
	matches := re.FindStringSubmatch(string(output))
	if len(matches) != 3 {
		return 0, 0, errors.New("failed to parse wm size output")
	}
	width, err := strconv.ParseInt(matches[1], 10, 32)
	if err != nil {
		return 0, 0, err
	}
	height, err := strconv.ParseInt(matches[2], 10, 32)
	if err != nil {
		return 0, 0, err
	}
	return int32(width), int32(height), nil
}
