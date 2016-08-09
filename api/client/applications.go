package client

import (
    "net/url"
    "encoding/json"
    "golang.org/x/net/context"
    "github.com/cloudway/platform/pkg/manifest"
)

func (api *APIClient) GetApplications(ctx context.Context) ([]string, error) {
    var apps []string
    resp, err := api.cli.Get(ctx, "/applications/", nil, nil)
    if err == nil {
        err = json.NewDecoder(resp.Body).Decode(&apps)
        resp.EnsureClosed()
    }
    return apps, err
}

func (api *APIClient) GetApplicationInfo(ctx context.Context, name string) (*manifest.ApplicationInfo, error) {
    var info manifest.ApplicationInfo
    resp, err := api.cli.Get(ctx, "/applications/"+name+"/info", nil, nil)
    if err == nil {
        err = json.NewDecoder(resp.Body).Decode(&info)
        resp.EnsureClosed()
    }
    return &info, err
}

func (api *APIClient) CreateApplication(ctx context.Context, opts manifest.CreateApplication) error {
    resp, err := api.cli.Post(ctx, "/applications/", nil, &opts, nil)
    resp.EnsureClosed()
    return err
}

func (api *APIClient) RemoveApplication(ctx context.Context, name string) error {
    resp, err := api.cli.Delete(ctx, "/applications/"+name, nil, nil)
    resp.EnsureClosed()
    return err
}

func (api *APIClient) StartApplication(ctx context.Context, name string) error {
    resp, err := api.cli.Post(ctx, "/applications/"+name+"/start", nil, nil, nil)
    resp.EnsureClosed()
    return err
}

func (api *APIClient) StopApplication(ctx context.Context, name string) error {
    resp, err := api.cli.Post(ctx, "/applications/"+name+"/stop", nil, nil, nil)
    resp.EnsureClosed()
    return err
}

func (api *APIClient) RestartApplication(ctx context.Context, name string) error {
    resp, err := api.cli.Post(ctx, "/applications/"+name+"/restart", nil, nil, nil)
    resp.EnsureClosed()
    return err
}

func (api *APIClient) DeployApplication(ctx context.Context, name, branch string) error {
    var query url.Values
    if branch != "" {
        query = url.Values{"branch": []string{branch}}
    }

    resp, err := api.cli.Post(ctx, "/applications/"+name+"/deploy", query, nil, nil)
    resp.EnsureClosed()
    return err
}

func (api *APIClient) GetApplicationDeployments(ctx context.Context, name string) (*manifest.ApplicationDeployments, error) {
    var deployments manifest.ApplicationDeployments
    resp, err := api.cli.Get(ctx, "/applications/"+name+"/deploy", nil, nil)
    if err == nil {
        err = json.NewDecoder(resp.Body).Decode(&deployments)
        resp.EnsureClosed()
    }
    return &deployments, err
}