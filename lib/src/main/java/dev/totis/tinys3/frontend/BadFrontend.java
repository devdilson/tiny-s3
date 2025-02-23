package dev.totis.tinys3.frontend;

public class BadFrontend {
  public static String FRONTEND =
          """
                  <!DOCTYPE html>
                  <html lang="en">
                  <head>
                      <meta charset="UTF-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1.0">
                      <title>TinyS3 Object Browser</title>
                      <script src="https://cdnjs.cloudflare.com/ajax/libs/aws-sdk/2.1442.0/aws-sdk.min.js"></script>
                      <script src="https://cdnjs.cloudflare.com/ajax/libs/feather-icons/4.29.0/feather.min.js"></script>
                      <script src="https://cdn.tailwindcss.com"></script>
                      <style>
                          /* Base styles for smooth transitions */
                          * {
                              transition: all 0.2s ease-in-out;
                          }
                  
                          /* Prevent content from causing horizontal overflow */
                          body {
                              overflow-x: hidden;
                              max-width: 100vw;
                          }
                  
                          /* Main content container */
                          #content {
                              max-height: 100vh;
                          }
                  
                          /* Table styles */
                          .table-container {
                              max-width: 100%;
                              overflow-x: auto;
                          }
                  
                          /* Table row hover styles */
                          tr {
                              cursor: pointer;
                          }
                  
                          /* Ensure table cells wrap content when needed */
                          .table-cell-wrap {
                              max-width: 0;
                              overflow: hidden;
                              text-overflow: ellipsis;
                              white-space: nowrap;
                          }
                  
                          /* Smooth button transitions */
                          button {
                              transition: all 0.2s ease-in-out;
                          }
                  
                          /* Smooth input transitions */
                          input {
                              transition: all 0.2s ease-in-out;
                          }
                  
                          /* Sidebar transition */
                          .sidebar {
                              transition: width 0.3s ease-in-out;
                          }
                  
                          /* Loading spinner animation */
                          @keyframes spin {
                              to {
                                  transform: rotate(360deg);
                              }
                          }
                      </style>
                  </head>
                  <body class="bg-gray-50">
                  <div id="loginForm" class="min-h-screen flex items-center justify-center">
                      <div class="w-full max-w-md p-8">
                          <div class="text-center mb-8">
                              <div class="flex items-center justify-center mb-2">
                                  <span class="text-blue-600 text-2xl font-bold">TinyS3</span>
                              </div>
                          </div>
                  
                          <form onsubmit="handleLogin(event)" class="space-y-4">
                              <div class="relative">
                                  <input type="text" id="endpoint" placeholder="Endpoint" value="http://localhost:8000" class="w-full px-4 py-3 border border-gray-200 rounded focus:ring-2 focus:ring-blue-500 focus:border-transparent">
                              </div>
                  
                              <div class="relative">
                                  <input type="text" id="accessKey" placeholder="Access Key" class="w-full px-4 py-3 border border-gray-200 rounded focus:ring-2 focus:ring-blue-500 focus:border-transparent">
                              </div>
                  
                              <div class="relative">
                                  <input type="password" id="secretKey" placeholder="Secret Key" class="w-full px-4 py-3 border border-gray-200 rounded focus:ring-2 focus:ring-blue-500 focus:border-transparent">
                              </div>
                  
                              <div id="loginError" class="text-red-500 text-sm hidden"></div>
                  
                              <button type="submit" class="w-full py-3 bg-gray-100 text-gray-800 rounded hover:bg-gray-200 transition-colors">
                                  Connect
                              </button>
                          </form>
                      </div>
                  </div>
                  
                  <div id="content" class="hidden min-h-screen flex overflow-hidden">
                      <div class="w-64 flex-shrink-0 bg-gray-900 text-white">
                          <div class="p-4">
                              <div class="flex items-center space-x-2 mb-8">
                                  <span class="text-blue-500 font-bold">TinyS3</span>
                              </div>
                  
                              <nav class="space-y-2">
                                  <a href="#" class="flex items-center space-x-2 px-4 py-2 rounded text-gray-300 hover:bg-gray-800">
                                      <i data-feather="hard-drive" class="w-5 h-5"></i>
                                      <span>Object Browser</span>
                                  </a>
                                  <a href="#" class="flex items-center space-x-2 px-4 py-2 rounded text-gray-300 hover:bg-gray-800">
                                      <i data-feather="key" class="w-5 h-5"></i>
                                      <span>Access Keys</span>
                                  </a>
                                  <a href="#" class="flex items-center space-x-2 px-4 py-2 rounded text-gray-300 hover:bg-gray-800">
                                      <i data-feather="file-text" class="w-5 h-5"></i>
                                      <span>Documentation</span>
                                  </a>
                              </nav>
                          </div>
                      </div>
                  
                      <div class="flex-1 flex flex-col overflow-hidden">
                          <header class="bg-white border-b border-gray-200">
                              <div class="flex justify-between items-center px-3 py-4">
                                  <div class="flex items-center space-x-4">
                                      <button id="backButton" onclick="navigateBack()" class="hidden text-gray-600 hover:text-gray-900">
                                          <i data-feather="chevron-left" class="w-5 h-5"></i>
                                      </button>
                                      <span id="breadcrumb" class="text-gray-600 truncate">/</span>
                                  </div>
                                  <div class="flex items-center space-x-4">
                                      <div class="relative">
                                          <input type="text" placeholder="Filter Buckets" class="w-64 px-4 py-2 pr-8 border border-gray-200 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-transparent">
                                          <i data-feather="search" class="absolute right-3 top-2.5 w-4 h-4 text-gray-400"></i>
                                      </div>
                                      <button onclick="handleLogout()" class="text-gray-600 hover:text-gray-900">
                                          <i data-feather="log-out" class="w-5 h-5"></i>
                                      </button>
                                  </div>
                              </div>
                          </header>
                  
                          <main class="flex-1 p-6 overflow-auto">
                              <div class="mb-6 flex justify-between items-center">
                                  <div class="flex space-x-4">
                                      <button id="newBucketButton" onclick="toggleCreateBucketForm()" class="flex items-center px-4 py-2 bg-gray-100 text-gray-800 rounded hover:bg-gray-200">
                                          <i data-feather="folder-plus" class="w-4 h-4 mr-2"></i>
                                          New Bucket
                                      </button>
                                      <input type="file" id="fileInput" class="hidden" onchange="handleFileUpload(event)">
                                      <button id="uploadButton" onclick="document.getElementById('fileInput').click()" class="hidden flex items-center px-4 py-2 bg-gray-100 text-gray-800 rounded hover:bg-gray-200">
                                          <i data-feather="upload" class="w-4 h-4 mr-2"></i>
                                          Upload
                                      </button>
                                  </div>
                              </div>
                  
                              <div id="createBucketForm" class="mb-6 hidden">
                                  <div class="flex space-x-4">
                                      <input type="text" id="newBucketName" placeholder="Enter bucket name" class="flex-1 px-4 py-2 border border-gray-200 rounded focus:ring-2 focus:ring-blue-500 focus:border-transparent">
                                      <button onclick="createBucket()" class="px-4 py-2 bg-gray-100 text-gray-800 rounded hover:bg-gray-200">
                                          Create
                                      </button>
                                  </div>
                              </div>
                  
                              <div id="loading" class="hidden">
                                  <div class="flex justify-center items-center h-64">
                                      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-gray-900"></div>
                                  </div>
                              </div>
                  
                              <div class="bg-white rounded-lg shadow overflow-hidden">
                                  <div class="overflow-x-auto table-container">
                                      <table class="min-w-full divide-y divide-gray-200">
                                          <thead class="bg-gray-50">
                                          <tr>
                                              <th class="px-3 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider w-2/5">Name</th>
                                              <th class="px-3 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider w-1/5">Objects</th>
                                              <th class="px-3 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider w-1/5">Size</th>
                                              <th class="px-3 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider w-1/5">Actions</th>
                                          </tr>
                                          </thead>
                                          <tbody id="fileList" class="bg-white divide-y divide-gray-200"></tbody>
                                      </table>
                                  </div>
                              </div>
                          </main>
                      </div>
                  </div>
                  
                  <script>
                      document.addEventListener('DOMContentLoaded', () => {
                          feather.replace();
                      });
                  
                      let s3;
                      let currentPath = [];
                  
                      window.addEventListener('load', () => {
                          const savedCredentials = localStorage.getItem('s3credentials');
                          if (savedCredentials) {
                              const creds = JSON.parse(savedCredentials);
                              document.getElementById('endpoint').value = creds.endpoint;
                              document.getElementById('accessKey').value = creds.accessKey;
                              document.getElementById('secretKey').value = creds.secretKey;
                              initializeS3(creds);
                          }
                      });
                  
                      function showError(message) {
                          const errorElement = document.getElementById('loginError');
                          errorElement.textContent = message;
                          errorElement.classList.remove('hidden');
                      }
                  
                      function hideError() {
                          const errorElement = document.getElementById('loginError');
                          errorElement.classList.add('hidden');
                      }
                  
                      function showLoading(show = true) {
                          document.getElementById('loading').style.display = show ? 'block' : 'none';
                          document.getElementById('fileList').style.display = show ? 'none' : 'table-row-group';
                      }
                  
                      function formatBytes(bytes) {
                          if (bytes === 0) return '0 Bytes';
                          const k = 1024;
                          const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
                          const i = Math.floor(Math.log(bytes) / Math.log(k));
                          return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                      }
                  
                      function formatDate(date) {
                          return new Date(date).toLocaleString();
                      }
                  
                      function handleLogin(event) {
                          event.preventDefault();
                          hideError();
                  
                          const credentials = {
                              endpoint: document.getElementById('endpoint').value,
                              accessKey: document.getElementById('accessKey').value,
                              secretKey: document.getElementById('secretKey').value
                          };
                  
                          initializeS3(credentials);
                      }
                  
                      function initializeS3(credentials) {
                          const { endpoint, accessKey, secretKey } = credentials;
                  
                          AWS.config.update({
                              accessKeyId: accessKey,
                              secretAccessKey: secretKey,
                              endpoint: endpoint,
                              s3ForcePathStyle: true,
                              signatureVersion: 'v4',
                              region: 'us-east-1',
                              httpOptions: {
                                  cors: false,
                                  withCredentials: true
                              }
                          });
                  
                          s3 = new AWS.S3();
                  
                          listBuckets()
                              .then(() => {
                                  localStorage.setItem('s3credentials', JSON.stringify(credentials));
                                  document.getElementById('loginForm').style.display = 'none';
                                  document.getElementById('content').style.display = 'flex';
                              })
                              .catch(error => {
                                  showError('Connection failed: ' + error.message);
                              });
                      }
                  
                      function handleLogout() {
                          localStorage.removeItem('s3credentials');
                          location.reload();
                      }
                  
                      function toggleCreateBucketForm() {
                          const form = document.getElementById('createBucketForm');
                          form.classList.toggle('hidden');
                      }
                  
                      async function createBucket() {
                          const bucketName = document.getElementById('newBucketName').value;
                          if (!bucketName) return;
                  
                          try {
                              showLoading();
                              await s3.createBucket({ Bucket: bucketName }).promise();
                              document.getElementById('createBucketForm').classList.add('hidden');
                              document.getElementById('newBucketName').value = '';
                              await listBuckets();
                          } catch (error) {
                              alert('Failed to create bucket: ' + error.message);
                          } finally {
                              showLoading(false);
                          }
                      }
                  
                      async function listBuckets() {
                          try {
                              showLoading();
                              const data = await s3.listBuckets().promise();
                              const fileList = document.getElementById('fileList');
                              fileList.innerHTML = '';
                  
                              data.Buckets.forEach(bucket => {
                                  const tr = document.createElement('tr');
                                  tr.className = 'hover:bg-gray-50';
                                  tr.innerHTML = `
                                      <td class="px-3 py-4 whitespace-nowrap">
                                          <div class="flex items-center">
                                              <i data-feather="database" class="w-5 h-5 text-gray-400 mr-2"></i>
                                              <span class="text-sm text-gray-900">${bucket.Name}</span>
                                          </div>
                                      </td>
                                      <td class="px-3 py-4 whitespace-nowrap text-sm text-gray-500">-</td>
                                      <td class="px-3 py-4 whitespace-nowrap text-sm text-gray-500">
                                          ${formatDate(bucket.CreationDate)}
                                      </td>
                                      <td class="px-3 py-4 whitespace-nowrap text-right text-sm font-medium">
                                          <div class="flex justify-end space-x-2">
                                              <button class="text-red-600 hover:text-red-900" onclick="deleteBucket('${bucket.Name}')">
                                                  <i data-feather="trash-2" class="w-5 h-5"></i>
                                              </button>
                                          </div>
                                      </td>
                                  `;
                                  tr.onclick = (e) => {
                                      if (!e.target.closest('button')) {
                                          navigateToBucket(bucket.Name);
                                      }
                                  };
                                  fileList.appendChild(tr);
                              });
                  
                              feather.replace();
                              currentPath = [];
                              updateNavigation();
                          } finally {
                              showLoading(false);
                          }
                      }
                  
                      async function listObjects(bucket, prefix = '') {
                          try {
                              showLoading();
                              const params = {
                                  Bucket: bucket,
                                  Prefix: prefix
                              };
                  
                              const data = await s3.listObjects(params).promise();
                              const fileList = document.getElementById('fileList');
                              fileList.innerHTML = '';
                  
                              data.Contents.forEach(object => {
                                  const tr = document.createElement('tr');
                                  tr.className = 'hover:bg-gray-50';
                                  tr.innerHTML = `
                                      <td class="px-3 py-4 whitespace-nowrap">
                                          <div class="flex items-center">
                                              <i data-feather="file" class="w-5 h-5 text-gray-400 mr-2"></i>
                                              <span class="text-sm text-gray-900">${object.Key}</span>
                                          </div>
                                      </td>
                                      <td class="px-3 py-4 whitespace-nowrap text-sm text-gray-500">
                                          ${formatBytes(object.Size)}
                                      </td>
                                      <td class="px-3 py-4 whitespace-nowrap text-sm text-gray-500">
                                          ${formatDate(object.LastModified)}
                                      </td>
                                      <td class="px-3 py-4 whitespace-nowrap text-right text-sm font-medium">
                                          <div class="flex justify-end space-x-2">
                                              <button class="text-blue-600 hover:text-blue-900" onclick="getSignedUrl('${object.Key}')">
                                                  <i data-feather="download" class="w-5 h-5"></i>
                                              </button>
                                              <button class="text-red-600 hover:text-red-900" onclick="deleteObject('${object.Key}')">
                                                  <i data-feather="trash-2" class="w-5 h-5"></i>
                                              </button>
                                          </div>
                                      </td>
                                  `;
                                  fileList.appendChild(tr);
                              });
                  
                              feather.replace();
                              updateNavigation();
                          } finally {
                              showLoading(false);
                          }
                      }
                  
                      function updateNavigation() {
                          const backButton = document.getElementById('backButton');
                          const uploadButton = document.getElementById('uploadButton');
                          const newBucketButton = document.getElementById('newBucketButton');
                          const breadcrumb = document.getElementById('breadcrumb');
                  
                          breadcrumb.textContent = '/ ' + currentPath.join(' / ');
                          backButton.style.display = currentPath.length > 0 ? 'flex' : 'none';
                          uploadButton.style.display = currentPath.length > 0 ? 'flex' : 'none';
                          newBucketButton.style.display = currentPath.length === 0 ? 'flex' : 'none';
                      }
                  
                      function navigateBack() {
                          if (currentPath.length === 1) {
                              listBuckets();
                          } else {
                              currentPath.pop();
                              listObjects(currentPath[0], currentPath.slice(1).join('/'));
                          }
                      }
                  
                      function navigateToBucket(bucket) {
                          currentPath = [bucket];
                          listObjects(bucket);
                      }
                  
                      async function handleFileUpload(event) {
                          const file = event.target.files[0];
                          if (!file) return;
                  
                          try {
                              showLoading();
                              const bucket = currentPath[0];
                              const key = currentPath.length > 1 ?
                                  currentPath.slice(1).join('/') + '/' + file.name :
                                  file.name;
                  
                              const params = {
                                  Bucket: bucket,
                                  Key: key,
                                  Body: file
                              };
                  
                              await s3.upload(params).promise();
                              listObjects(bucket, currentPath.slice(1).join('/'));
                          } catch (error) {
                              alert('Upload failed: ' + error.message);
                          } finally {
                              showLoading(false);
                              event.target.value = '';
                          }
                      }
                  
                      async function getSignedUrl(key) {
                          try {
                              showLoading();
                              const params = {
                                  Bucket: currentPath[0],
                                  Key: key,
                                  Expires: 60 * 5, // URL expires in 5 minutes
                                  ResponseContentDisposition: 'attachment; filename="' + key.split('/').pop() + '"'
                              };
                  
                              const signedUrl = await s3.getSignedUrlPromise('getObject', params);
                              window.location.href = signedUrl;
                          } catch (error) {
                              alert('Download failed: ' + error.message);
                          } finally {
                              showLoading(false);
                          }
                      }
                  
                  
                      async function deleteObject(key) {
                          if (!confirm('Are you sure you want to delete this object?')) return;
                  
                          try {
                              showLoading();
                              const params = {
                                  Bucket: currentPath[0],
                                  Key: key
                              };
                  
                              await s3.deleteObject(params).promise();
                              listObjects(currentPath[0], currentPath.slice(1).join('/'));
                          } catch (error) {
                              alert('Delete failed: ' + error.message);
                          } finally {
                              showLoading(false);
                          }
                      }
                  
                      async function deleteBucket(bucket) {
                          if (!confirm('Are you sure you want to delete this bucket?')) return;
                  
                          try {
                              showLoading();
                              await s3.deleteBucket({ Bucket: bucket }).promise();
                              listBuckets();
                          } catch (error) {
                              alert('Delete failed: ' + error.message);
                          } finally {
                              showLoading(false);
                          }
                      }
                  </script>
                  </body>
                  </html>
                  """;
}
